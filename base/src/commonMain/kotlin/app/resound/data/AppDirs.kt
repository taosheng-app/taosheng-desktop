package app.resound.data

import java.io.File

/**
 * 应用目录（对应安卓 `filesDir` / `noBackupFilesDir` / `cacheDir`）。
 * - [data]：持久数据（数据库、录音、设置、备份暂存）。
 * - [cache]：可随时丢弃的临时文件（导入解码、导出暂存、下载中的更新包）。
 * - [logs]：日志与崩溃记录。
 * 系统属性 `resound.home` 可整体覆盖（测试、便携模式），此时 cache/logs 位于其下。
 */
class AppDirs(val data: File, val cache: File, val logs: File) {
    init { listOf(data, cache, logs).forEach { it.mkdirs() } }

    fun data(vararg parts: String): File = parts.fold(data) { dir, part -> File(dir, part) }
    fun cache(vararg parts: String): File = parts.fold(cache) { dir, part -> File(dir, part) }

    companion object {
        enum class Os { Windows, Mac, Linux }

        fun currentOs(name: String = System.getProperty("os.name") ?: ""): Os = name.lowercase().let {
            when {
                it.contains("win") -> Os.Windows
                it.contains("mac") || it.contains("darwin") -> Os.Mac
                else -> Os.Linux
            }
        }

        /**
         * 纯函数：按平台约定计算路径，便于在任意系统上测试三种布局。
         * 环境变量缺失时回退到用户目录下的惯例位置。
         */
        fun layout(os: Os, home: String, env: Map<String, String>): Triple<File, File, File> = when (os) {
            Os.Windows -> {
                val roaming = env["APPDATA"]?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
                val local = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local"
                Triple(File(roaming, "Taosheng"), File(local, "Taosheng\\Cache"), File(local, "Taosheng\\Logs"))
            }
            Os.Mac -> Triple(
                File(home, "Library/Application Support/Taosheng"),
                File(home, "Library/Caches/Taosheng"),
                File(home, "Library/Logs/Taosheng"),
            )
            Os.Linux -> {
                val data = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
                val cache = env["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.cache"
                val state = env["XDG_STATE_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/state"
                Triple(File(data, "taosheng"), File(cache, "taosheng"), File(state, "taosheng/logs"))
            }
        }

        fun system(): AppDirs {
            System.getProperty("resound.home")?.takeIf { it.isNotBlank() }?.let { return under(File(it)) }
            val (data, cache, logs) = layout(currentOs(), System.getProperty("user.home"), System.getenv())
            return AppDirs(data, cache, logs)
        }

        /** 所有目录都放在 [root] 下（测试与便携模式）。 */
        fun under(root: File) = AppDirs(File(root, "data"), File(root, "cache"), File(root, "logs"))
    }
}