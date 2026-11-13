package app.resound.platform

import app.resound.platform.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 平台层装配：一处创建，业务层通过 AppGraph 取用。对应安卓 `ResoundApp.onCreate` 里与系统相关的那部分。
 * 桌面入口调用 [create]；测试用 [create] 传入 `AppDirs.under(tempDir)`。
 */
class DesktopPlatform(
    val dirs: AppDirs,
    val clock: Clock = Clock.System,
    val network: NetworkMonitor = InterfaceNetworkMonitor(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 钥匙串回退到文件时回调（只一次），界面可提示用户。 */
    onKeyFallback: (Throwable?) -> Unit = {},
    /** 默认系统钥匙串；测试注入 [FileMasterKeyProvider]，避免往真实钥匙串写东西。 */
    masterKeys: MasterKeyProvider? = null,
) {
    /** 对应 `getSharedPreferences(name, MODE_PRIVATE)`。 */
    val prefs = KeyValueStores(dirs.data("prefs"))
    val masterKeys: MasterKeyProvider = masterKeys ?: KeyringMasterKeyProvider(dirs.data("keys"), onFallback = onKeyFallback)
    val notifier = DesktopNotifier(prefs.get("notifications"))
    val scheduler = Scheduler(File(dirs.data, "scheduler.json"), clock, network, scope)

    /** 对应 `SecretStore(context, prefsName, alias)`：密文存在同名 prefs 里。 */
    fun secretStore(prefsName: String, alias: String) = SecretStore(prefs.get(prefsName), alias, masterKeys)

    /** 进程退出前调用：落盘 apply() 的内容、停止调度。 */
    fun shutdown() {
        scheduler.stop()
        prefs.flushAll()
    }

    companion object {
        fun create(
            dirs: AppDirs = AppDirs.system(),
            onKeyFallback: (Throwable?) -> Unit = {},
            masterKeys: MasterKeyProvider? = null,
        ) = DesktopPlatform(dirs, onKeyFallback = onKeyFallback, masterKeys = masterKeys)
    }
}