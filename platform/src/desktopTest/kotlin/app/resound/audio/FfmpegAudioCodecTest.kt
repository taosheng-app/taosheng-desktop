package app.resound.audio

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 任务卡 001 验证 D：ffmpeg 子进程编码 OGG/Opus、M4A/AAC，再解回 PCM，时长一致。
 * 找不到 ffmpeg 时跳过（CI 可用环境变量 RESOUND_FFMPEG 指定）。
 */
class FfmpegAudioCodecTest {
    private val ffmpeg = FfmpegLocator.find()
    private val dir: File = Files.createTempDirectory("codec").toFile().also { it.deleteOnExit() }

    /** 3 秒、440Hz 正弦，16k/单声道/16bit。 */
    private fun tonePcm(seconds: Int = 3): File {
        val n = 16_000 * seconds
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * 440 * i / 16_000) * 12_000).toInt().toShort().toInt()
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        return File(dir, "tone$seconds.pcm").also { it.writeBytes(bytes) }
    }

    private fun roundTrip(container: AudioCodec.Container) {
        if (ffmpeg == null) { println("SKIP: 没有 ffmpeg"); return }
        val codec = FfmpegAudioCodec(ffmpeg)
        val pcm = tonePcm()
        val encoded = codec.encode(pcm, File(dir, "seg-${container.extension}"), container)
        assertTrue(encoded.name.endsWith("." + container.extension))
        assertTrue(encoded.length() in 1000..60_000, "24kbps 的 3 秒音频应为几 KB，实际 ${encoded.length()}")
        assertTrue(!File(encoded.path + ".part").exists(), "不应遗留 .part")
        val back = File(dir, "back-${container.extension}.pcm")
        val samples = codec.decodeToPcm(encoded, back)
        // 编解码有帧对齐（Opus 20ms、AAC 1024 样本）与前导延迟，允许 ±0.1 秒
        assertTrue(kotlin.math.abs(samples - 48_000) <= 1_600, "解码样本数 $samples 与原 48000 差距过大")
    }

    @Test fun ogg_opus_round_trip() = roundTrip(AudioCodec.Container.OggOpus)

    @Test fun m4a_aac_round_trip() = roundTrip(AudioCodec.Container.M4aAac)

    @Test
    fun missing_ffmpeg_gives_readable_error_and_bad_input_cleans_up() {
        val none = FfmpegAudioCodec(ffmpeg = null)
        val e = assertFailsWith<AudioCodecException> { none.encode(tonePcm(1), File(dir, "x")) }
        assertTrue(e.message!!.contains("ffmpeg"))
        if (ffmpeg == null) return
        val bad = File(dir, "bad.ogg").also { it.writeText("这不是音频") }
        val out = File(dir, "bad.pcm")
        assertFailsWith<AudioCodecException> { FfmpegAudioCodec(ffmpeg).decodeToPcm(bad, out) }
        assertEquals(false, out.exists(), "失败后不应遗留半成品")
    }
}