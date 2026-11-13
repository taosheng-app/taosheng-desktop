package app.resound.desktop

import app.resound.data.AppDirs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Copies user-selected audio into the app-owned import cache and probes its duration. */
internal object DesktopAudioFiles {
    private val supported = setOf("wav", "ogg", "pcm", "m4a", "mp3", "mp4", "aac", "flac", "webm", "opus")
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val MAX_DURATION_MS = 12L * 60 * 60 * 1000

    fun import(source: File, dirs: AppDirs): ImportedAudio {
        require(source.isFile && source.length() > 0L) { "请选择有效的音频文件。" }
        require(source.length() <= MAX_BYTES) { "音频文件不能超过 1 GB。" }
        val extension = source.extension.lowercase()
        require(extension in supported) { "支持 WAV、OGG、PCM、M4A、MP3、MP4、AAC、FLAC、WEBM、OPUS。" }
        val duration = probeDuration(source)
        require(duration in 1L..MAX_DURATION_MS) { "音频时长无效或超过 12 小时。" }
        val directory = dirs.cache("import-segments").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        val staging = File(directory, ".${destination.name}.partial")
        try {
            Files.copy(source.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return ImportedAudio(destination, duration)
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            staging.delete()
        }
    }

    private fun probeWaveDuration(source: File): Long? = RandomAccessFile(source, "r").use { input ->
        if (input.length() < 12) return@use null
        val header = ByteArray(12)
        input.readFully(header)
        if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WAVE") return@use null
        var byteRate = 0L
        var audioFormat = 0
        var dataSize = 0L
        while (input.filePointer + 8 <= input.length()) {
            val id = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val size = readU32Le(input)
            val chunkEnd = input.filePointer + size
            if (chunkEnd > input.length()) return@use null
            when (id) {
                "fmt " -> {
                    if (size < 16) return@use null
                    audioFormat = readU16Le(input)
                    readU16Le(input) // channel count
                    readU32Le(input) // sample rate
                    byteRate = readU32Le(input)
                }
                "data" -> dataSize = size
            }
            if (audioFormat != 0 && dataSize > 0) break
            input.seek(chunkEnd + (size and 1L))
        }
        if (audioFormat !in setOf(1, 3, 0xfffe) || byteRate <= 0 || dataSize <= 0) return@use null
        (dataSize * 1000 / byteRate).takeIf { it > 0 }
    }

    private fun readU16Le(input: RandomAccessFile): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun readU32Le(input: RandomAccessFile): Long =
        input.readUnsignedByte().toLong() or (input.readUnsignedByte().toLong() shl 8) or
            (input.readUnsignedByte().toLong() shl 16) or (input.readUnsignedByte().toLong() shl 24)

    private fun probeDuration(source: File): Long {
        when (source.extension.lowercase()) {
            "pcm" -> return (source.length() * 1000 / 32_000).also { require(it > 0) { "PCM 音频长度无效。" } }
            "wav" -> probeWaveDuration(source)?.let { return it }
        }
        val executable = System.getenv("TAOSHENG_FFPROBE")?.takeIf { it.isNotBlank() } ?: "ffprobe"
        val process = try {
            ProcessBuilder(executable, "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source.absolutePath)
                .redirectErrorStream(true).start()
        } catch (_: IOException) {
            throw IllegalStateException("未找到 ffprobe，请安装 FFmpeg 并将 ffprobe 加入 PATH。")
        }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("读取音频时长超时。")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.exitValue() != 0) throw IllegalArgumentException("无法读取音频文件，请检查文件是否损坏。")
        val seconds = output.toDoubleOrNull() ?: throw IllegalArgumentException("无法读取音频时长。")
        require(seconds.isFinite() && seconds > 0 && seconds <= MAX_DURATION_MS / 1000.0) { "音频时长无效或超过 12 小时。" }
        return (seconds * 1000).toLong().coerceAtLeast(1)
    }
}

internal data class ImportedAudio(val file: File, val durationMillis: Long)
