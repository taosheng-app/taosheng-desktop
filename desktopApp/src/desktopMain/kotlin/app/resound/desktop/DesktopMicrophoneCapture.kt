package app.resound.desktop

import app.resound.data.AppDirs
import app.resound.data.SegmentEntity
import app.resound.data.TranscribeDao
import app.resound.platform.audio.AudioRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Explicit, foreground-only microphone capture into the existing cloud-ASR import queue. */
class DesktopMicrophoneCapture(
    private val dirs: AppDirs,
    private val dao: TranscribeDao,
    private val recorder: AudioRecorder,
    private val scope: CoroutineScope,
) : AutoCloseable {
    data class State(val isRecording: Boolean, val message: String)

    private class Session(val file: File, val startedAt: Long) {
        lateinit var job: Job
        var bytesWritten: Long = 0
        var failure: Throwable? = null
    }

    private class CaptureLimitReached : Exception()

    private val lock = Any()
    private var active: Session? = null
    private val _state = MutableStateFlow(State(false, "录音未开始。"))
    val state = _state.asStateFlow()

    fun start(): String = synchronized(lock) {
        check(active == null) { "当前已有录音正在进行。" }
        val directory = dirs.cache("import-segments")
        check(directory.mkdirs() || directory.isDirectory) { "无法创建录音缓存目录。" }
        val session = Session(File(directory, "recording-${UUID.randomUUID()}.pcm"), System.currentTimeMillis())
        active = session
        _state.value = State(true, "录音中。音频只保存在本机；点击“停止并保存”后才加入转写队列。")
        session.job = scope.launch(Dispatchers.IO) { record(session) }
        _state.value.message
    }

    suspend fun stop(): String {
        val session = synchronized(lock) {
            active?.also { active = null }
        } ?: return state.value.message

        session.job.cancelAndJoin()
        val failure = session.failure
        if (failure != null) {
            session.file.delete()
            return publish(State(false, "麦克风录音失败：${failure.message ?: failure::class.simpleName}"))
        }
        return enqueue(session)
    }

    private suspend fun record(session: Session) {
        var queueWhenDone = false
        try {
            session.file.outputStream().buffered().use { output ->
                recorder.record().collect { chunk ->
                    val elapsed = System.currentTimeMillis() - session.startedAt
                    if (elapsed >= MAX_DURATION_MS || session.bytesWritten + chunk.size > MAX_FILE_BYTES) {
                        queueWhenDone = true
                        throw CaptureLimitReached()
                    }
                    output.write(chunk)
                    session.bytesWritten += chunk.size
                    if (session.bytesWritten >= MAX_FILE_BYTES || elapsed >= MAX_DURATION_MS) {
                        queueWhenDone = true
                        throw CaptureLimitReached()
                    }
                }
            }
            queueWhenDone = true // Also persist if the platform capture stream ends naturally.
        } catch (_: CaptureLimitReached) {
            queueWhenDone = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            session.failure = failure
        }

        if (queueWhenDone && session.failure == null) {
            val message = enqueue(session)
            synchronized(lock) { if (active === session) active = null }
            _state.value = State(false, message)
        } else if (session.failure != null) {
            session.file.delete()
            synchronized(lock) { if (active === session) active = null }
            _state.value = State(false, "麦克风录音失败：${session.failure?.message ?: "无法读取麦克风"}")
        }
    }

    private suspend fun enqueue(session: Session): String = try {
        val bytes = session.file.length()
        val durationMillis = bytes * 1000L / BYTES_PER_SECOND
        if (durationMillis < MIN_DURATION_MS) {
            session.file.delete()
            "录音不足 1 秒，未加入转写队列。"
        } else {
            val row = SegmentEntity(
                startAt = session.startedAt,
                endAt = session.startedAt + durationMillis,
                sourceId = "import",
                path = session.file.absolutePath,
                sizeBytes = bytes,
                status = "Pending",
            )
            val id = dao.insertImportedAudioSegment(row)
            check(id > 0L) { "无法将录音加入转写队列。" }
            "录音已保存到本机并加入队列；点击“开始云端转写”后才会上传。"
        }
    } catch (failure: Throwable) {
        session.file.delete()
        "无法保存录音：${failure.message ?: failure::class.simpleName}"
    }

    private fun publish(next: State): String {
        _state.value = next
        return next.message
    }

    override fun close() {
        val session = synchronized(lock) {
            active?.also {
                active = null
                _state.value = State(false, "正在停止录音…")
            }
        } ?: return
        session.job.cancel()
        session.job.invokeOnCompletion { session.file.delete() }
    }

    private companion object {
        const val BYTES_PER_SECOND = 32_000L // PCM16, 16 kHz, mono
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 12L * 60 * 60 * 1000
        const val MAX_FILE_BYTES = 1L shl 30
    }
}
