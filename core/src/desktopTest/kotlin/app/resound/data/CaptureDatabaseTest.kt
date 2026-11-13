package app.resound.data

import app.resound.search.SearchTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 任务卡 003：完整 CaptureDatabase 上的关键行为（FTS 三套索引、触发器、外键级联、事务、关系、失效订阅）。 */
class CaptureDatabaseTest {
    private val now = 1_700_000_000_000L

    private suspend fun CaptureDatabase.exec(sql: String, vararg args: Any?) = rawExec(sql, *args)
    private suspend fun CaptureDatabase.count(sql: String, vararg args: Any?) = rawQuery(sql, *args) { it.long(0) }.single()

    private suspend fun CaptureDatabase.seed(): Unit {
        exec("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?,'mic','a.ogg',1,'Done')", now, now + 1000)
        exec("""INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model)
            VALUES(1,?,?,'其他','交通票据','明天把车费发票交给财务','["整理票据"]','[]',0,0,?,'test')""", now, now + 1000, now)
        exec("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(1,1,'待办','交通票据交给财务','Pending',?)", now)
        exec("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(1,1,?,?,'明天把车费发票交给财务',1,'我')", now, now + 1000)
    }

    private suspend fun CaptureDatabase.hits(table: String, word: String): List<Long> {
        val term = SearchTokens.terms(listOf(word)).single()
        return rawQuery("SELECT rowid FROM $table WHERE $table MATCH ? ORDER BY rowid", term) { it.long(0) }
    }

    @Test
    fun three_fts_indexes_follow_inserts_updates_and_deletes() = runBlocking {
        val db = CaptureDatabase.openInMemory()
        try {
            db.seed()
            assertEquals(listOf(1L), db.hits("lines_fts", "发票"))
            assertEquals(listOf(1L), db.hits("events_fts", "交通"))
            assertEquals(listOf(1L), db.hits("items_fts", "财务"))
            // 更新：旧词查不到，新词能查到
            db.exec("UPDATE transcript_lines SET text='后天去银行办卡' WHERE id=1")
            assertEquals(emptyList(), db.hits("lines_fts", "发票"))
            assertEquals(listOf(1L), db.hits("lines_fts", "银行"))
            // 事件“隐藏”：触发器清掉它与其提取项的 embeddings（安卓 search_event_hidden）
            db.exec("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('event',1,'m',1,x'00',?)", now)
            db.exec("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('item',1,'m',1,x'00',?)", now)
            db.exec("UPDATE memory_events SET deleted=1 WHERE id=1")
            assertEquals(0L, db.count("SELECT COUNT(*) FROM embeddings"))
        } finally { db.close() }
    }

    @Test
    fun foreign_key_cascades_and_delete_triggers_stay_in_sync() = runBlocking {
        val db = CaptureDatabase.openInMemory()
        try {
            db.seed()
            db.exec("INSERT INTO digest_pending(id,lineIds,content,day,promptTokens,completionTokens) VALUES('p','1,7','x','2026-11-13',1,1)")
            // 删片段 → 级联删句子 → digest_pending 的删除触发器作废引用该句的待提交记录，lines_fts 同步
            db.exec("DELETE FROM segments WHERE id=1")
            assertEquals(0L, db.count("SELECT COUNT(*) FROM transcript_lines"))
            assertEquals(0L, db.count("SELECT COUNT(*) FROM digest_pending"))
            assertEquals(emptyList(), db.hits("lines_fts", "发票"))
            // 删事件 → 级联删提取项
            db.exec("DELETE FROM memory_events WHERE id=1")
            assertEquals(0L, db.count("SELECT COUNT(*) FROM memory_items"))
        } finally { db.close() }
    }

    @Test
    fun transaction_rolls_back_all_writes_including_triggers() = runBlocking {
        val db = CaptureDatabase.openInMemory()
        try {
            assertFailsWith<IllegalStateException> {
                db.inTransaction {
                    db.seed()
                    error("boom")
                }
            }
            assertEquals(0L, db.count("SELECT COUNT(*) FROM transcript_lines"))
            assertEquals(0L, db.count("SELECT COUNT(*) FROM lines_fts"), "FTS 触发器的写入也应回滚")
        } finally { db.close() }
    }

    @Test
    fun relation_query_and_abstract_dao_transaction_methods_work() = runBlocking {
        val db = CaptureDatabase.openInMemory()
        try {
            db.seed()
            val withLines = db.transcribe().exportSegments(listOf(1L))
            assertEquals(1, withLines.size)
            assertEquals(listOf("明天把车费发票交给财务"), withLines.single().lines.map { it.text })
        } finally { db.close() }
    }

    /**
     * 安卓原 `@RawQuery(observedEntities=[memory_events, memory_items, embeddings])` 的替代写法必须保持同样的失效语义：
     * 三张表里**任何一张**变化都要重新发射。每张表单独验证，且每次写入前先等发射安静下来，
     * 避免“上一次写入迟到的发射”冒充“这一次的发射”。
     */
    @Test
    fun review_materialChanges_fires_for_each_of_the_three_tables_individually() = runBlocking {
        val db = CaptureDatabase.openInMemory()
        try {
            db.seed() // 先建好数据，再开始观察
            val emissions = java.util.concurrent.atomic.AtomicInteger()
            val job = db.review().materialChanges().onEach { emissions.incrementAndGet() }.launchIn(CoroutineScope(Dispatchers.Default))

            /** 等到发射次数在 300ms 内不再变化，返回稳定后的次数。 */
            suspend fun quiet(): Int = withTimeout(15_000) {
                var last = -1
                while (true) {
                    val now = emissions.get()
                    if (now == last && now > 0) return@withTimeout now
                    last = now
                    kotlinx.coroutines.delay(300)
                }
                @Suppress("UNREACHABLE_CODE") 0
            }

            suspend fun assertFires(what: String, write: suspend () -> Unit) {
                val before = quiet()
                write()
                val after = quiet()
                assertTrue(after > before, "只改 $what 应触发 materialChanges 重新发射（$before → $after）")
            }

            assertFires("memory_events") { db.exec("UPDATE memory_events SET title='改标题' WHERE id=1") }
            assertFires("memory_items") { db.exec("UPDATE memory_items SET status='Done' WHERE id=1") }
            assertFires("embeddings") {
                db.exec("INSERT INTO embeddings(kind,refId,model,dim,vector,updatedAt) VALUES('event',1,'m',1,x'00',?)", now)
            }
            job.cancel()
        } finally { db.close() }
    }
}