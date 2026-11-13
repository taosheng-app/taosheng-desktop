package app.resound.platform

import app.resound.data.AppDirs
import java.io.File
import java.util.concurrent.TimeUnit

/** 开机自启开关。实现按系统使用其原生机制，均为当前用户级（不需要管理员权限）。 */
interface AutoStart {
    /** 当前能否使用：开发环境（非安装包运行）没有可执行文件路径，此时为 false。 */
    val isSupported: Boolean
    fun isEnabled(): Boolean

    /** 失败抛 [IllegalStateException]。 */
    fun setEnabled(enabled: Boolean)
}

/**
 * - Windows：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 下的 `Taosheng` 值（经 `reg.exe`）。
 * - macOS：`~/Library/LaunchAgents/app.resound.desktop.plist`（`RunAtLoad`，下次登录生效）。
 * - Linux：`$XDG_CONFIG_HOME/autostart/taosheng.desktop`（freedesktop autostart）。
 *
 * [launchCommand] 默认取 jpackage 安装包注入的系统属性 `jpackage.app-path`；
 * 从 Gradle `run` 启动时没有该属性，[isSupported] 为 false，避免把 java 命令写进自启项。
 */
class DesktopAutoStart(
    private val launchCommand: List<String>? = defaultLaunchCommand(),
    private val os: AppDirs.Companion.Os = AppDirs.currentOs(),
    private val home: String = System.getProperty("user.home") ?: "",
    private val env: Map<String, String> = System.getenv(),
    private val exec: (List<String>) -> Int = { runProcess(it) },
) : AutoStart {
    override val isSupported: Boolean get() = !launchCommand.isNullOrEmpty()

    private val plistFile get() = File(home, "Library/LaunchAgents/$MAC_LABEL.plist")
    private val desktopFile get() = File(env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.config", "autostart/taosheng.desktop")

    override fun isEnabled(): Boolean = when (os) {
        AppDirs.Companion.Os.Windows -> exec(listOf("reg", "query", WIN_RUN_KEY, "/v", WIN_VALUE_NAME)) == 0
        AppDirs.Companion.Os.Mac -> plistFile.isFile
        AppDirs.Companion.Os.Linux -> desktopFile.isFile
    }

    override fun setEnabled(enabled: Boolean) {
        val cmd = launchCommand
        if (enabled && cmd.isNullOrEmpty()) throw IllegalStateException("当前不是安装包运行，无法设置开机自启")
        try {
            when (os) {
                AppDirs.Companion.Os.Windows ->
                    if (enabled) {
                        check(exec(listOf("reg", "add", WIN_RUN_KEY, "/v", WIN_VALUE_NAME, "/t", "REG_SZ", "/d", windowsRunValue(cmd!!), "/f")) == 0) { "写入注册表失败" }
                    } else if (isEnabled()) {
                        check(exec(listOf("reg", "delete", WIN_RUN_KEY, "/v", WIN_VALUE_NAME, "/f")) == 0) { "删除注册表项失败" }
                    }
                AppDirs.Companion.Os.Mac -> write(plistFile, enabled) { launchAgentPlist(cmd!!) }
                AppDirs.Companion.Os.Linux -> write(desktopFile, enabled) { desktopEntry(cmd!!) }
            }
        } catch (e: IllegalStateException) { throw e } catch (e: Exception) {
            throw IllegalStateException("设置开机自启失败：${e.message}", e)
        }
    }

    private fun write(file: File, enabled: Boolean, content: () -> String) {
        if (enabled) {
            file.parentFile?.mkdirs()
            file.writeText(content())
        } else {
            file.delete()
        }
    }

    companion object {
        const val WIN_RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val WIN_VALUE_NAME = "Taosheng"
        const val MAC_LABEL = "app.resound.desktop"

        fun defaultLaunchCommand(): List<String>? =
            System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { listOf(it) }

        /** Run 值：每个参数加双引号。 */
        fun windowsRunValue(cmd: List<String>): String = cmd.joinToString(" ") { "\"${it.replace("\"", "\\\"")}\"" }

        fun launchAgentPlist(cmd: List<String>): String = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("<dict>")
            appendLine("    <key>Label</key><string>$MAC_LABEL</string>")
            appendLine("    <key>ProgramArguments</key>")
            appendLine("    <array>")
            cmd.forEach { appendLine("        <string>${xmlEscape(it)}</string>") }
            appendLine("    </array>")
            appendLine("    <key>RunAtLoad</key><true/>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        fun desktopEntry(cmd: List<String>): String = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=涛声")
            appendLine("Exec=" + cmd.joinToString(" ") { execQuote(it) })
            appendLine("Terminal=false")
            appendLine("X-GNOME-Autostart-enabled=true")
        }

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** freedesktop Exec 引用规则：含空白或特殊字符时加双引号，并转义 `"` `` ` `` `$` `\`。 */
        private fun execQuote(s: String): String {
            if (s.isNotEmpty() && s.none { it.isWhitespace() || it in "\"'\\><~|&;$*?#()`" }) return s
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\""
        }

        internal fun runProcess(args: List<String>): Int = try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            p.inputStream.use { it.readBytes() }
            if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
        } catch (e: Exception) { -1 }
    }
}
