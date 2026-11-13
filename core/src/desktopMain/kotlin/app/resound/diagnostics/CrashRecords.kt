package app.resound.diagnostics

import app.resound.data.AppDirs
import java.io.File
import java.util.UUID

/**
 * 不调用 Throwable.toString/printStackTrace，避免 message、文件名或自定义线程名泄露内容。
 *
 * 桌面版：崩溃记录放在 [AppDirs.logs]/crash（安卓是 `filesDir/crash`）。
 * [version] 由调用方给出（安卓版读 `PackageManager`，桌面没有对应接口）。
 * TODO(base/platform 暂无应用版本接口)：待 platform 提供 AppInfo/BuildInfo 后改为读取它，目前由装配处传入。
 */
internal class CrashRecords(
    dirs: AppDirs,
    private val version: String = "versionName=unknown versionCode=0",
) {
    private val directory = File(dirs.logs, "crash")

    fun installHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(previous))
    }

    internal fun handler(previous: Thread.UncaughtExceptionHandler?) = Thread.UncaughtExceptionHandler { thread, error ->
        try { runCatching { record(thread, error) } }
        finally {
            // 安卓版先 Process.killProcess 再 exit；JVM 只需 exit。
            if (previous != null) previous.uncaughtException(thread, error)
            else kotlin.system.exitProcess(10)
        }
    }

    @Synchronized internal fun record(thread: Thread, error: Throwable, at: Long = System.currentTimeMillis()) {
        check(directory.isDirectory || directory.mkdirs())
        val text = buildString {
            appendLine("at=$at $version")
            val safeName = thread.name.takeIf { it.matches(Regex("main|Thread-[0-9]+|DefaultDispatcher-worker-[0-9]+|pool-[0-9]+-thread-[0-9]+|WM.task-[0-9]+")) }
            appendLine("thread=${safeName ?: "自定义线程"} id=${thread.id}")
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            fun trace(value: Throwable, depth: Int) {
                if (depth >= 8 || !seen.add(value)) return
                appendLine("exception=${value.javaClass.name}")
                value.stackTrace.take(128).forEach { appendLine("  ${it.className}:${it.lineNumber}") }
                value.cause?.let { appendLine("cause:"); trace(it, depth + 1) }
                value.suppressed.take(4).forEach { appendLine("suppressed:"); trace(it, depth + 1) }
            }
            trace(error, 0)
        }
        val file = File(directory, "%019d-%s.txt".format(at, UUID.randomUUID()))
        val stage = File(directory, file.name + ".part")
        try {
            stage.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(stage.renameTo(file))
            files().dropLast(10).forEach { check(it.delete()) }
        } finally { stage.delete() }
    }

    @Synchronized fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "txt" }.sortedBy { it.name }
    fun latestAt(): Long? = files().lastOrNull()?.name?.substringBefore('-')?.toLongOrNull()
    fun dump(): String = files().takeLast(10).joinToString("\n") { it.readText() }
}
