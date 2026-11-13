package app.resound.audio

import java.io.File

/**
 * 音频编解码端口：对应安卓 `SegmentEncoder`（MediaCodec/MediaMuxer）与 `AsrAudioReader`/`ExportPcmDecoder`（MediaExtractor）。
 * 所有 PCM 均为 16kHz / 单声道 / 16 位有符号小端（见 [AudioSource]）。
 */
interface AudioCodec {
    /**
     * 把暂存 PCM 编码为 [Container]，写到 `base + "." + 扩展名`，先写 `.part` 再改名；返回最终文件。
     * [bitRate] 单位 bit/s，安卓版为 24_000。
     */
    fun encode(pcm: File, base: File, container: Container = Container.OggOpus, bitRate: Int = 24_000): File

    /** 解码任意 ffmpeg 支持的音频到 16k/单声道/16bit PCM，写入 [out]，返回样本数。 */
    fun decodeToPcm(input: File, out: File): Long

    enum class Container(val extension: String) { OggOpus("ogg"), M4aAac("m4a") }
}

/** 找不到或无法运行 ffmpeg，或编解码失败。消息给人看，不带命令行以免泄露路径之外的信息。 */
class AudioCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)