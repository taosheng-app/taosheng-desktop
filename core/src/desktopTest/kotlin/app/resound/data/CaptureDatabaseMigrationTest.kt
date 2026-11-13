package app.resound.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 迁移测试：用安卓保留的历史 schema（1～33.json，原样拷在测试资源里）还原旧版数据库，
 * 再用桌面的 `CaptureDatabase.MIGRATIONS` 升到最新版，核对结构与数据。
 * 全程用 `CaptureDatabase.VERSION` 与 `MIGRATIONS`，不写死数字（AGENTS.md 的教训）。
 */
class CaptureDatabaseMigrationTest {
    private fun schema(version: Int): JSONObject {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("android-schemas/$version.json")) { "缺少 $version.json" }
        val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }
        return JSONObject(text).getJSONObject("database")
    }

    /** 按安卓导出的 schema 建出“旧版本”的库文件：建表、索引、setupQueries（含 FTS 与 room_master_table）。 */
    private fun createOldDatabase(file: File, version: Int) {
        val driver = BundledSQLiteDriver()
        driver.open(file.absolutePath).use { conn ->
            val s = schema(version)
            val entities = s.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(v: String) = v.replace("\${TABLE_NAME}", entity.getString("tableName"))
                conn.prepare(sql(entity.getString("createSql"))).use { it.step() }
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length())
                    conn.prepare(sql(indices.getJSONObject(j).getString("createSql"))).use { it.step() }
            }
            val queries = s.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) conn.prepare(queries.getString(i)).use { it.step() }
            conn.prepare("PRAGMA user_version = $version").use { it.step() }
        }
    }

    private fun tempFile(name: String) = File(kotlin.io.path.createTempDirectory("mig").toFile(), name)

    @Test
    fun every_old_version_migrates_to_latest_with_same_structure_as_android() = runBlocking {
        // 所有可迁移的起点（1 .. VERSION-1）逐个验证，最终结构必须与最新 schema 一致（Room 打开时会校验 identityHash）
        val start = 1
        val failures = mutableListOf<String>()
        for (from in start until CaptureDatabase.VERSION) {
            val file = tempFile("v$from.db")
            try {
                createOldDatabase(file, from)
                val db = CaptureDatabase.open(file)
                try {
                    // rawQuery 会触发打开 + 迁移 + identityHash 校验
                    val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") { it.text(0) }.toSet()
                    val expected = schema(CaptureDatabase.VERSION).getJSONArray("entities").let { arr ->
                        (0 until arr.length()).map { arr.getJSONObject(it).getString("tableName") }
                    }
                    val missing = expected.filter { it !in tables }
                    if (missing.isNotEmpty()) failures += "v$from → 缺表 $missing"
                } finally { db.close() }
            } catch (e: Throwable) {
                failures += "v$from → ${e::class.simpleName}: ${e.message?.take(160)}"
            }
        }
        assertTrue(failures.isEmpty(), "以下起始版本迁移失败：\n" + failures.joinToString("\n"))
    }

    @Test
    fun old_data_survives_migration_and_reopening_is_idempotent() = runBlocking {
        val file = tempFile("v3.db")
        createOldDatabase(file, 3)
        // 往 v3 的 battery 表写一行旧数据（与安卓 AppPowerMigrationTest 同款）
        BundledSQLiteDriver().open(file.absolutePath).use { c -> c.prepare("INSERT INTO battery VALUES (1,1000,80,0)").use { it.step() } }

        var db = CaptureDatabase.open(file)
        assertEquals(80, db.capture().batteries(0, 2000).first().single().percent)
        db.close()

        // 重复打开：回调里的 CREATE TRIGGER IF NOT EXISTS 不应重复创建，也不应报错
        db = CaptureDatabase.open(file)
        val triggers1 = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'") { it.long(0) }.single()
        db.close()
        db = CaptureDatabase.open(file)
        val triggers2 = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'") { it.long(0) }.single()
        db.close()
        assertEquals(triggers1, triggers2)
        assertTrue(triggers1 > 0, "应存在搜索/级联触发器")
    }
}