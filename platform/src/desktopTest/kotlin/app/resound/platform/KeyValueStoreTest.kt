package app.resound.platform
import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.FileKeyValueStore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueStoreTest {
    private fun file() = File(kotlin.io.path.createTempDirectory("kv").toFile(), "prefs.json")

    @Test
    fun values_round_trip_across_reopen_with_types_preserved() {
        val f = file()
        FileKeyValueStore(f).edit()
            .putString("s", "你好").putInt("i", 7).putLong("l", 1L shl 40).putBoolean("b", true)
            .putFloat("f", 1.5f).putStringSet("set", setOf("a", "b")).commit()
        val again = FileKeyValueStore(f)
        assertEquals("你好", again.getString("s", null))
        assertEquals(7, again.getInt("i", 0))
        assertEquals(1L shl 40, again.getLong("l", 0))
        assertTrue(again.getBoolean("b", false))
        assertEquals(1.5f, again.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), again.getStringSet("set", null))
        // 类型不符返回默认值而不是抛异常
        assertEquals(-1, again.getInt("s", -1))
        assertNull(again.getString("i", null))
    }

    @Test
    fun corrupt_file_falls_back_to_empty_and_keeps_evidence() {
        val f = file().also { it.writeText("{这不是 json") }
        val store = FileKeyValueStore(f)
        assertFalse(store.contains("x"))
        assertTrue(File(f.path + ".corrupt").isFile, "应保留损坏文件供排查")
        assertTrue(store.edit().putString("x", "1").commit())
        assertEquals("1", FileKeyValueStore(f).getString("x", null))
    }

    @Test
    fun listeners_fire_for_changed_keys_and_null_removes() {
        val store = FileKeyValueStore(file())
        val seen = mutableListOf<String?>()
        store.addListener { seen += it }
        store.edit().putString("a", "1").putInt("b", 2).commit()
        store.edit().putString("a", "1").commit() // 值没变，不通知
        store.edit().putString("a", null).commit() // 等同 remove
        assertEquals(listOf<String?>("a", "b", "a"), seen)
        assertFalse(store.contains("a"))
    }

    @Test
    fun concurrent_edits_do_not_lose_updates() {
        val f = file()
        val store = FileKeyValueStore(f)
        val threads = (0 until 8).map { t -> Thread { repeat(25) { n -> store.edit().putInt("k-$t-$n", n).commit() } } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(200, store.all.size)
        assertEquals(200, FileKeyValueStore(f).all.size, "落盘后数量也应一致")
    }

    @Test
    fun apply_is_flushed_by_flush() {
        val f = file()
        val store = FileKeyValueStore(f)
        store.edit().putString("late", "v").apply()
        store.flush()
        assertEquals("v", FileKeyValueStore(f).getString("late", null))
    }
}