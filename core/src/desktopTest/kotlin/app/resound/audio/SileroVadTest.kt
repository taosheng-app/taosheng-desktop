package app.resound.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** 任务卡 001 验证 B：ONNX Runtime（JVM）加载安卓同一份 silero_vad.onnx，静音低、人声高。 */
class SileroVadTest {
    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "缺少测试资源 $name" }.use { it.readBytes() }

    /** 读 16k/单声道/16bit 的 WAV（找 data 块，不假设固定 44 字节头）。 */
    private fun wavSamples(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(len, bytes.size - pos - 8) / 2
                return ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + len + (len and 1)
        }
        error("WAV 没有 data 块")
    }

    /** 逐 512 样本跑一遍，返回每帧概率（用 EXTERNAL 之外的 DEFAULT 灵敏度，阈值 0.5）。 */
    private fun speechFrames(samples: ShortArray): Pair<Int, Int> {
        SileroVad(resource("silero_vad.onnx")).use { vad ->
            var speech = 0; var total = 0
            vad.accept(samples) { _, isSpeech, _ -> total++; if (isSpeech) speech++ }
            vad.finish { _, isSpeech, _ -> total++; if (isSpeech) speech++ }
            return speech to total
        }
    }

    @Test
    fun silence_and_low_noise_are_not_speech() {
        val rnd = Random(1)
        val samples = ShortArray(16_000 * 3) { (rnd.nextInt(-30, 30)).toShort() } // 约 -60 dBFS 底噪
        val (speech, total) = speechFrames(samples)
        assertTrue(total > 90, "应处理约 93 帧，实际 $total")
        assertTrue(speech == 0, "底噪不应被判为人声，误判 $speech/$total 帧")
    }

    @Test
    fun synthesized_speech_is_detected() {
        val samples = wavSamples(resource("speech16k.wav"))
        assertTrue(samples.size > 16_000 * 4, "TTS 样本应超过 4 秒，实际 ${samples.size} 样本")
        val (speech, total) = speechFrames(samples)
        // 句子中间有停顿，所以不要求全部为人声；有超过三成的帧被判为人声即可证明模型与协议接对了
        assertTrue(speech * 100 / total >= 30, "人声检出率过低：$speech/$total")
    }
}