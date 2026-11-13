package app.resound.platform
import app.resound.data.AppDirs
import app.resound.data.KeyValueStore
import app.resound.data.FileKeyValueStore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** 装配冒烟：一个入口拿到全部端口，secretStore 与 prefs 联动，shutdown 后重开数据仍在。 */
class DesktopPlatformTest {
    @Test
    fun wiring_works_and_data_survives_restart() {
        val root = kotlin.io.path.createTempDirectory("plat").toFile()
        val p1 = DesktopPlatform.create(AppDirs.under(root), masterKeys = FileMasterKeyProvider(File(root, "keys")))
        p1.prefs.get("capture").edit().putLong("started", 42L).apply()
        p1.secretStore("digest", "llm").write("key", "sk-test")
        p1.shutdown()
        val p2 = DesktopPlatform.create(AppDirs.under(root), masterKeys = FileMasterKeyProvider(File(root, "keys")))
        assertEquals(42L, p2.prefs.get("capture").getLong("started", 0))
        assertEquals("sk-test", p2.secretStore("digest", "llm").read("key"))
        assertEquals(true, File(root, "data/prefs/capture.json").isFile)
    }
}