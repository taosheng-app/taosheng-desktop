package app.resound.data

import app.resound.search.SearchTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 任务卡 001 验证 A：Room KMP + BundledSQLiteDriver 能否承载涛声的 FTS4、触发器、事务、Flow、裸 SQL。 */
class SpikeDatabaseTest {
    private fun newDb() = SpikeDatabaseFactory.open(null)

    private suspend fun SpikeDatabase.seed(vararg texts: String): Long {
        val seg = dao().segment(SegmentEntity(startAt = 1000, endAt = 9000, sourceId = "mic", path = "a.ogg", sizeBytes = 1))
        texts.forEachIndexed { i, t ->
            dao().line(TranscriptLineEntity(segmentId = seg, startAt = 1000L + i, endAt = 2000L + i, text = t, speakerTag = null))
        }
        return seg
    }

    /** 与安卓搜索一致：每个关键词一条 MATCH，词内空格=隐式 AND。 */
    private suspend fun SpikeDatabase.search(word: String): List<Long> {
        val term = SearchTokens.terms(listOf(word)).single()
        return queryLongs("SELECT rowid FROM lines_fts WHERE lines_fts MATCH ? ORDER BY rowid", term)
    }

    @Test
    fun fts4_chinese_match_and_triggers() = runBlocking {
        val db = newDb()
        try {
            db.seed("明天下午三点开会讨论预算", "记得给张三打电话", "Project Alpha 周五上线")
            // 中文二元分词：“开会”“预算”能命中；无关词不命中
            assertEquals(listOf(1L), db.search("开会"))
            assertEquals(listOf(2L), db.search("张三"))
            assertEquals(emptyList(), db.search("火星"))
            // 英文按小写词匹配，和大小写无关
            assertEquals(listOf(3L), db.search("alpha"))
            // 删除触发器：删段落级联删句子后索引同步
            db.dao().deleteSegment(1)
            assertEquals(emptyList(), db.search("开会"))
        } finally { db.close() }
    }

    @Test
    fun transaction_rolls_back_on_failure() = runBlocking {
        val db = newDb()
        try {
            val seg = db.dao().segment(SegmentEntity(startAt = 1, endAt = 2, sourceId = "mic", path = "t.ogg", sizeBytes = 1))
            assertFailsWith<IllegalStateException> {
                db.inTransaction {
                    db.dao().line(TranscriptLineEntity(segmentId = seg, startAt = 1, endAt = 2, text = "不该留下", speakerTag = null))
                    error("boom")
                }
            }
            assertTrue(db.dao().lines(seg).isEmpty(), "事务内的写入应被回滚")
            assertEquals(emptyList(), db.search("留下"), "FTS 触发器写入同样应被回滚")
        } finally { db.close() }
    }

    @Test
    fun flow_query_emits_after_insert() = runBlocking {
        val db = newDb()
        try {
            assertEquals(0, db.dao().segments(0, 10_000).first().size)
            db.dao().segment(SegmentEntity(startAt = 1000, endAt = 2000, sourceId = "mic", path = "f.ogg", sizeBytes = 1))
            assertEquals(1, db.dao().segments(0, 10_000).first().size)
        } finally { db.close() }
    }
}