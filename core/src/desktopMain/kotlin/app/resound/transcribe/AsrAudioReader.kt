package app.resound.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException

/** OGG 原始页按约 200ms 合并；压缩音频由 ffmpeg 流式解码至 PCM16/16k/mono。 */
class AsrAudioReader(private val cacheDir: File) {
    data class Prepared(val file: File, val format: String, val temporary: Boolean) : AutoCloseable {
        override fun close() { if (temporary) file.delete() }
    }

    suspend fun prepare(audio: AsrAudio): Prepared = withContext(Dispatchers.IO) {
        if (!audio.file.isFile || audio.file.length() == 0L) throw AsrException(AsrError.Audio)
        when (audio.format) {
            "ogg", "pcm", "wav" -> Prepared(audio.file, audio.format, false)
            "m4a", "mp3", "mp4", "aac", "flac", "webm", "opus" -> {
                val target = File.createTempFile("asr-", ".pcm", cacheDir)
                try { decode(audio.file, target); Prepared(target, "pcm", true) }
                catch (e: Exception) { target.delete(); if (e is kotlinx.coroutines.CancellationException) throw e; throw AsrException(AsrError.Audio) }
            }
            else -> throw AsrException(AsrError.Audio)
        }
    }

    suspend fun readPcm(source: File): ShortArray = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0) { "录音已过期或不存在" }
        readNativePcm16kMono(source)?.let { return@withContext it }
        val target = File.createTempFile("asr-voiceprint-", ".pcm", cacheDir)
        try {
            decode(source, target)
            target.inputStream().buffered().use { input ->
                val bytes = input.readBytes()
                require(bytes.isNotEmpty() && bytes.size % 2 == 0)
                ShortArray(bytes.size / 2) { i -> ByteBuffer.wrap(bytes, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short }
            }
        } finally { target.delete() }
    }

    /** Reads the desktop import formats without FFmpeg when their PCM layout is already canonical. */
    private fun readNativePcm16kMono(source: File): ShortArray? {
        if (source.extension.equals("pcm", ignoreCase = true)) {
            val bytes = source.readBytes()
            if (bytes.isEmpty() || bytes.size % 2 != 0) return null
            return ShortArray(bytes.size / 2) { i -> ByteBuffer.wrap(bytes, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short }
        }
        if (!source.extension.equals("wav", ignoreCase = true)) return null
        val bytes = source.readBytes()
        if (bytes.size < 44 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") return null
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val size = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            val start = offset + 8
            if (size > Int.MAX_VALUE || start.toLong() + size > bytes.size) return null
            when (id) {
                "fmt " -> if (size >= 16) {
                    val fmt = ByteBuffer.wrap(bytes, start, size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    format = fmt.short.toInt() and 0xffff
                    channels = fmt.short.toInt() and 0xffff
                    rate = fmt.int
                    fmt.int // byte rate
                    fmt.short // block alignment
                    bits = fmt.short.toInt() and 0xffff
                }
                "data" -> { dataOffset = start; dataSize = size.toInt() }
            }
            if (dataOffset >= 0 && format != 0) break
            offset = (start.toLong() + size + (size and 1L)).toInt()
        }
        if (format != 1 || channels != 1 || rate != 16_000 || bits != 16 || dataOffset < 0 || dataSize == 0 || dataSize % 2 != 0) return null
        return ShortArray(dataSize / 2) { i -> ByteBuffer.wrap(bytes, dataOffset + i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short }
    }

    internal suspend fun decodeForExport(source: File, target: File) = withContext(Dispatchers.IO) { decode(source, target) }

    private suspend fun decode(source: File, target: File) {
        try {
            target.outputStream().buffered().use { output ->
                ExportPcmDecoder.decode(source) { output.write(it) }
            }
        } catch (e: IOException) {
            if (e.message?.contains("CreateProcess") == true || e.cause is IOException) {
                throw IllegalStateException("未找到 ffmpeg，请安装并加入 PATH", e)
            }
            throw e
        }
        check(target.length() > 0)
    }

    companion object {
        suspend fun chunks(prepared: Prepared, send: suspend (ByteArray, Boolean) -> Unit) {
            prepared.file.inputStream().buffered().use { input ->
                val ogg = if (prepared.format == "ogg") OggFrames(input) else null
                fun nextChunk() = ogg?.next() ?: if (ogg == null) chunk(input, prepared.format) else null
                var current = nextChunk() ?: throw AsrException(AsrError.Audio)
                while (true) {
                    val next = nextChunk()
                    send(current, next == null)
                    if (next == null) break
                    current = next
                }
            }
        }

        /** Opus granule 固定为 48k 时钟；不拆页、不改头/校验和、不转码。 */
        private class OggFrames(private val input: InputStream) {
            private var granule = 0L
            fun next(): ByteArray? {
                val bytes = ByteArrayOutputStream()
                var samples = 0L
                do {
                    val page = chunk(input, "ogg") ?: break
                    bytes.write(page)
                    val end = ByteBuffer.wrap(page, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    if (end >= 0) { samples += (end - granule).coerceAtLeast(0); granule = end }
                } while (samples < 9_600 && bytes.size() < 64 * 1024)
                return bytes.takeIf { it.size() > 0 }?.toByteArray()
            }
        }

        private fun read(input: InputStream, count: Int): ByteArray {
            val data = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val n = input.read(data, offset, count - offset)
                if (n < 0) throw AsrException(AsrError.Audio)
                offset += n
            }
            return data
        }

        private fun chunk(input: InputStream, format: String): ByteArray? {
            val first = input.read()
            if (first < 0) return null
            if (format != "ogg") {
                val bytes = ByteArray(6400); bytes[0] = first.toByte()
                var size = 1
                while (size < bytes.size) {
                    val n = input.read(bytes, size, bytes.size - size)
                    if (n < 0) break
                    size += n
                }
                if (size % 2 != 0) throw AsrException(AsrError.Audio)
                return bytes.copyOf(size)
            }
            val header = byteArrayOf(first.toByte()) + read(input, 26)
            if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "OggS" || header[4].toInt() != 0) throw AsrException(AsrError.Audio)
            val laces = read(input, header[26].toInt() and 255)
            return header + laces + read(input, laces.sumOf { it.toInt() and 255 })
        }
    }
}

/** 以全局样本位置线性插值，跨解码缓冲区保留相位。 */
internal class PcmResampler(private val sourceRate: Int) {
    private var index = 0L
    private var next = 0L
    private var previous = 0
    fun accept(sample: Int, output: (Int) -> Unit) {
        while (next * sourceRate <= index * 16000L) {
            val fraction = (next * sourceRate - (index - 1) * 16000L) / 16000.0
            output(if (index == 0L) sample else (previous + (sample - previous) * fraction).toInt().coerceIn(-32768, 32767))
            next++
        }
        previous = sample; index++
    }
    fun finish(output: (Int) -> Unit) {
        while (next * sourceRate < index * 16000L) { output(previous); next++ }
    }
}
