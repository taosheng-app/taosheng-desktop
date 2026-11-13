package app.resound.audio

import java.io.File

/**
 * 查找 ffmpeg 可执行文件。顺序：
 * 1. 系统属性 `resound.ffmpeg`（开发/测试指定）
 * 2. 环境变量 `RESOUND_FFMPEG`
 * 3. 安装包随带目录：系统属性 `compose.application.resources.dir`（Compose 打包后的资源目录）下的 `ffmpeg/`
 * 4. 系统 PATH
 * 找不到返回 null，由调用方给出“请安装/重装”的提示，而不是崩溃。
 */
object FfmpegLocator {
    private val exeName = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"

    fun find(): File? {
        System.getProperty("resound.ffmpeg")?.let { File(it).takeIf(File::canExecute)?.let { f -> return f } }
        System.getenv("RESOUND_FFMPEG")?.let { File(it).takeIf(File::canExecute)?.let { f -> return f } }
        System.getProperty("compose.application.resources.dir")?.let {
            File(it, "ffmpeg/$exeName").takeIf(File::canExecute)?.let { f -> return f }
        }
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator).asSequence().filter { it.isNotBlank() }
            .map { File(it, exeName) }.firstOrNull { it.isFile && it.canExecute() }
    }
}