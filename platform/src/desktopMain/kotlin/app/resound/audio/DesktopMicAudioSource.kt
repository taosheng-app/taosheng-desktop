package app.resound.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * 桌面麦克风：`javax.sound.sampled`，16kHz / 单声道 / 16 位有符号小端，每 30 毫秒（480 采样）一帧。
 * 对应安卓 `MicAudioSource`；蓝牙路由、让麦、看门狗等安卓专有逻辑不在本卡范围。
 *
 * - 取消收集即停止并释放设备（`finally` 里 stop/close）。
 * - 没有设备或设备被独占 → [AudioSourceException]（Disconnected / Busy），不抛底层异常。
 * - 系统拒绝麦克风权限（macOS/Windows 隐私开关）时多数表现为读到全零或打开失败，这里按打开失败映射为 NoPermission/Busy。
 */
class DesktopMicAudioSource(
    override val id: String = "mic",
    override val displayName: String = "麦克风",
    /** 指定混音器名称包含的字样；null 用系统默认录音设备。 */
    private val deviceNameContains: String? = null,
) : AudioSource {

    override fun events(): Flow<AudioEvent> = flow {
        val format = AudioFormat(AudioSource.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val line = openLine(format)
        try {
            line.start()
            val bytes = ByteArray(AudioSource.FRAME_SAMPLES * 2)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < bytes.size) {
                    val n = line.read(bytes, filled, bytes.size - filled)
                    if (n < 0) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入设备已断开")
                    // 设备被关闭或停止时 read 会返回 0；视为断开，避免空转
                    if (n == 0 && !line.isOpen) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入设备已关闭")
                    filled += n
                }
                val samples = ShortArray(AudioSource.FRAME_SAMPLES) {
                    ((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xff)).toShort()
                }
                emit(AudioEvent.Frame(samples))
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun openLine(format: AudioFormat): TargetDataLine {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        try {
            val line = if (deviceNameContains == null) {
                if (!AudioSystem.isLineSupported(info))
                    throw AudioSourceException(AudioSourceException.Reason.Disconnected, "没有可用的录音设备")
                AudioSystem.getLine(info) as TargetDataLine
            } else {
                val mixer = AudioSystem.getMixerInfo().firstOrNull {
                    it.name.contains(deviceNameContains, ignoreCase = true) && AudioSystem.getMixer(it).isLineSupported(info)
                } ?: throw AudioSourceException(AudioSourceException.Reason.Disconnected, "找不到录音设备：$deviceNameContains")
                AudioSystem.getMixer(mixer).getLine(info) as TargetDataLine
            }
            // 缓冲约 200 毫秒，足够吸收调度抖动
            line.open(format, AudioSource.FRAME_SAMPLES * 2 * 7)
            return line
        } catch (e: AudioSourceException) {
            throw e
        } catch (e: LineUnavailableException) {
            throw AudioSourceException(AudioSourceException.Reason.Busy, "录音设备被占用或无权限")
        } catch (e: SecurityException) {
            throw AudioSourceException(AudioSourceException.Reason.NoPermission, "没有麦克风权限")
        } catch (e: IllegalArgumentException) {
            throw AudioSourceException(AudioSourceException.Reason.Disconnected, "没有可用的录音设备")
        } catch (e: IOException) {
            throw AudioSourceException(AudioSourceException.Reason.Unknown, e.message)
        }
    }

    companion object {
        /** 列出能提供 16k/单声道/16bit 采集的设备名，便于设置页选择（后续卡使用）。 */
        fun availableDevices(): List<String> {
            val info = DataLine.Info(TargetDataLine::class.java, AudioFormat(AudioSource.SAMPLE_RATE.toFloat(), 16, 1, true, false))
            return AudioSystem.getMixerInfo().filter { AudioSystem.getMixer(it).isLineSupported(info) }.map { it.name }
        }
    }
}