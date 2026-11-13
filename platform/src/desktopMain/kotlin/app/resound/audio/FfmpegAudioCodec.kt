package app.resound.audio

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用 ffmpeg 子进程实现 [AudioCodec]。
 * - Opus/OGG：`libopus`，`-application voip`（语音）；AAC/M4A：内置 `aac` 编码器（LGPL 构建可用）。
 * - 子进程的 stdout/stderr 合并后丢弃到临时日志，避免管道写满卡死；失败时只回报末尾几行。
 */
class FfmpegAudioCodec(private val ffmpeg: File? = FfmpegLocator.find()) : AudioCodec {

    private fun exe(): File = ffmpeg
        ?: throw AudioCodecException("找不到 ffmpeg：请重新安装涛声，或设置环境变量 RESOUND_FFMPEG 指向 ffmpeg 可执行文件")

    override fun encode(pcm: File, base: File, container: AudioCodec.Container, bitRate: Int): File {
        val output = File(base.path + "." + container.extension)
        val partial = File(output.path + ".part")
        val codecArgs = when (container) {
            AudioCodec.Container.OggOpus -> listOf("-c:a", "libopus", "-application", "voip", "-b:a", bitRate.toString(), "-f", "ogg")
            AudioCodec.Container.M4aAac -> listOf("-c:a", "aac", "-b:a", bitRate.toString(), "-f", "ipod")
        }
        try {
            run(listOf("-f", "s16le", "-ar", "16000", "-ac", "1", "-i", pcm.path) + codecArgs + listOf(partial.path))
            if (partial.length() <= 0 || !partial.renameTo(output)) throw AudioCodecException("编码文件落盘失败")
            return output
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    override fun decodeToPcm(input: File, out: File): Long {
        try {
            run(listOf("-i", input.path, "-f", "s16le", "-ar", "16000", "-ac", "1", out.path))
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out.length() / 2
    }

    private fun run(args: List<String>) {
        val log = File.createTempFile("ffmpeg", ".log")
        try {
            val process = try {
                ProcessBuilder(listOf(exe().path, "-hide_banner", "-nostdin", "-y", "-loglevel", "error") + args)
                    .redirectErrorStream(true).redirectOutput(log).start()
            } catch (e: java.io.IOException) {
                throw AudioCodecException("无法运行 ffmpeg：${e.message}", e)
            }
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw AudioCodecException("ffmpeg 超时")
            }
            if (process.exitValue() != 0) {
                val tail = log.readLines().takeLast(3).joinToString(" | ").take(300)
                throw AudioCodecException("音频编解码失败（ffmpeg 退出码 ${process.exitValue()}）：$tail")
            }
        } finally {
            log.delete()
        }
    }
}