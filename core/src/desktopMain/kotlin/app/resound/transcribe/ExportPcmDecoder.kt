package app.resound.transcribe

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Streams decoded PCM16 mono at 16 kHz from ffmpeg. */
internal object ExportPcmDecoder {
    suspend fun decode(source: File, emit: suspend (ByteArray) -> Unit) {
        require(source.isFile) { "录音已过期或不存在" }
        val process = try {
            ProcessBuilder(ffmpeg(), "-v", "error", "-i", source.absolutePath, "-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "16000", "pipe:1")
                .redirectErrorStream(false).start()
        } catch (e: IOException) {
            throw IllegalStateException("未找到 ffmpeg，请安装并加入 PATH", e)
        }
        val errors = StringBuilder()
        val errorThread = Thread { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(errors) { if (errors.length < 8192) errors.appendLine(line) } } } }.apply { isDaemon = true; start() }
        var total = 0L
        try {
            process.inputStream.buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    val even = count - count % 2
                    if (even > 0) emit(buffer.copyOf(even))
                    if (even != count) throw IOException("ffmpeg 输出了不完整的 PCM16 样本")
                }
            }
            val exit = process.waitFor()
            errorThread.join(1000)
            check(exit == 0) { "ffmpeg 解码失败: ${synchronized(errors) { errors.toString().trim() }}" }
            check(total > 0) { "录音没有可解码的样本" }
        } catch (e: Throwable) {
            process.destroyForcibly()
            throw e
        } finally {
            process.outputStream.close()
        }
    }

    internal fun ffmpeg(): String = System.getenv("TAOSHENG_FFMPEG")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
}
