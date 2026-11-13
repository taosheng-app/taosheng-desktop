package app.resound.audio

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 任务卡 001 验证 C。需要真实麦克风：没有设备（如 CI）时自动跳过，不算失败。
 * Windows 本机已用「麦克风阵列 (Realtek)」「Wireless Mic Rx」实测。
 */
class DesktopMicAudioSourceTest {
    private val hasMic get() = DesktopMicAudioSource.availableDevices().isNotEmpty()

    @Test
    fun frames_are_30ms_and_cancel_releases_device() = runBlocking {
        if (!hasMic) { println("SKIP: 无录音设备"); return@runBlocking }
        val source = DesktopMicAudioSource()
        val started = System.nanoTime()
        val frames = withTimeout(10_000) { source.events().take(10).toList() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(10, frames.size)
        frames.forEach {
            val f = it as AudioEvent.Frame
            assertEquals(AudioSource.FRAME_SAMPLES, f.samples.size)
        }
        // 10 帧 = 300 毫秒音频；实时采集不应远快于实时（说明不是假数据），也不应慢到离谱
        assertTrue(elapsedMs in 150..5_000, "采集 10 帧耗时 ${elapsedMs}ms 不合理")
        // take 结束即取消：设备应已释放，立刻能再次打开
        val again = withTimeout(10_000) { source.events().take(2).toList() }
        assertEquals(2, again.size, "取消后设备未释放，无法再次打开")
    }

    @Test
    fun missing_device_maps_to_audio_source_exception_not_crash() = runBlocking {
        val source = DesktopMicAudioSource(deviceNameContains = "不存在的设备-xyz")
        val e = assertFailsWith<AudioSourceException> { source.events().take(1).toList() }
        assertEquals(AudioSourceException.Reason.Disconnected, e.reason)
    }
}