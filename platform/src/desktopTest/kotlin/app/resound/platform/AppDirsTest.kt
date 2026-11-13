package app.resound.platform
import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.FileKeyValueStore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppDirsTest {
    @Test
    fun layouts_follow_each_platforms_convention() {
        val (wd, wc, _) = AppDirs.layout(AppDirs.Companion.Os.Windows, "C:\\Users\\a",
            mapOf("APPDATA" to "C:\\Users\\a\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local"))
        assertEquals(File("C:\\Users\\a\\AppData\\Roaming", "Taosheng"), wd)
        assertEquals(File("C:\\Users\\a\\AppData\\Local", "Taosheng\\Cache"), wc)

        val (md, mc, _) = AppDirs.layout(AppDirs.Companion.Os.Mac, "/Users/a", emptyMap())
        assertEquals(File("/Users/a", "Library/Application Support/Taosheng"), md)
        assertEquals(File("/Users/a", "Library/Caches/Taosheng"), mc)

        // Linux：XDG 变量优先，缺失回退到惯例位置
        assertEquals(File("/x/data", "taosheng"), AppDirs.layout(AppDirs.Companion.Os.Linux, "/home/a", mapOf("XDG_DATA_HOME" to "/x/data")).first)
        assertEquals(File("/home/a/.local/share", "taosheng"), AppDirs.layout(AppDirs.Companion.Os.Linux, "/home/a", emptyMap()).first)
    }

    @Test
    fun home_override_puts_everything_under_root_and_creates_dirs() {
        val root = kotlin.io.path.createTempDirectory("home").toFile()
        val dirs = AppDirs.under(root)
        assertTrue(dirs.data.isDirectory && dirs.cache.isDirectory && dirs.logs.isDirectory)
        assertEquals(File(root, "data/a/b"), dirs.data("a", "b"))
    }

    @Test
    fun os_detection() {
        assertEquals(AppDirs.Companion.Os.Windows, AppDirs.currentOs("Windows 11"))
        assertEquals(AppDirs.Companion.Os.Mac, AppDirs.currentOs("Mac OS X"))
        assertEquals(AppDirs.Companion.Os.Linux, AppDirs.currentOs("Linux"))
    }
}