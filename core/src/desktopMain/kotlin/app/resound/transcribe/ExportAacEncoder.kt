package app.resound.transcribe

import java.io.File
import java.io.IOException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** PCM16/16 kHz/mono is streamed into ffmpeg and encoded as AAC in an M4A container. */
internal interface ExportPcmSink : AutoCloseable {
    suspend fun append(pcm: ByteArray)
    suspend fun silence()
    suspend fun finish()
}

internal class ExportAacEncoder(private val target: File, private val bitrate: Int = 64_000) : ExportPcmSink {
    private val process: Process
    private val errors = StringBuilder()
    private val errorThread: Thread
    private var samples = 0L
    private var finished = false

    init {
        require(bitrate > 0) { "AAC 比特率必须大于零" }
        target.parentFile?.mkdirs()
        try {
            process = ProcessBuilder(ExportPcmDecoder.ffmpeg(), "-y", "-v", "error", "-f", "s16le", "-ar", "16000", "-ac", "1", "-i", "pipe:0", "-c:a", "aac", "-b:a", bitrate.toString(), "-f", "ipod", target.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            throw IllegalStateException("未找到 ffmpeg，请安装并加入 PATH", e)
        }
        errorThread = Thread { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(errors) { if (errors.length < 8192) errors.appendLine(line) } } } }.apply { isDaemon = true; start() }
    }

    override suspend fun append(pcm: ByteArray) {
        require(pcm.size % 2 == 0)
        coroutineContext.ensureActive()
        process.outputStream.write(pcm)
        samples += pcm.size / 2
    }

    override suspend fun silence() = append(ByteArray(16_000))

    override suspend fun finish() {
        if (finished) return
        process.outputStream.close()
        val exit = process.waitFor()
        errorThread.join(1000)
        check(exit == 0) { "ffmpeg AAC 编码失败: ${synchronized(errors) { errors.toString().trim() }}" }
        check(samples > 0 && target.isFile && target.length() > 0) { "ffmpeg 未生成有效的 M4A 文件" }
        finished = true
    }

    override fun close() {
        if (!finished) {
            runCatching { process.outputStream.close() }
            process.destroyForcibly()
        }
    }
}
