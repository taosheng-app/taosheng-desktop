package app.resound.transcribe

import app.resound.data.AppDirs
import app.resound.data.TranscribeDao
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import java.io.File

internal const val IMPORT_RETENTION = 3 * 24 * 60 * 60 * 1000L

/** 年代久远的导入录音也从本次生成临时片段的时间算 3 天，不从录制时间算。 */
internal suspend fun cleanupImportedAudio(dao: TranscribeDao, now: Long, reading: Set<Long> = emptySet()) {
    for (segment in dao.importedAudio()) {
        if (segment.status in setOf("Done", "Empty", "SkippedPlayback", "SkippedEnv")) continue
        if (segment.id in reading) continue
        val file = File(segment.path)
        if (!file.exists() || now - file.lastModified() < IMPORT_RETENTION) continue
        if (file.delete()) dao.update(segment.copy(status = "Abandoned", completedAt = segment.completedAt ?: now,
            attempts = 5, nextRetryAt = null,
            error = segment.error ?: "导入音频已满 3 天并删除，请重新分享"))
    }
    dao.refreshFailures(now)
}

/** Desktop scheduled cleanup; dependencies are supplied by the composition root. */
class ImportAudioCleanupWork(
    private val dao: TranscribeDao,
    private val runner: TranscribeRunner,
    private val cleanupVoiceprints: suspend () -> Unit,
    private val dirs: AppDirs,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun doWork(ctx: TaskContext): TaskResult = try {
        if (ctx.isCancelled) return TaskResult.Retry
        runner.withIdleAudio {
            cleanupVoiceprints()
            cleanupImportedAudio(dao, now())
            val importDirectory = dirs.cache("import-segments")
            importDirectory.listFiles()?.filter { now() - it.lastModified() >= IMPORT_RETENTION }?.forEach { file ->
                if (!dao.hasImportPath(file.path, file.path)) file.delete()
            }
        }
        TaskResult.Success
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { TaskResult.Retry }

    fun register(scheduler: Scheduler) {
        scheduler.registerWorker(WORKER, ::doWork)
        scheduler.enqueuePeriodic(PERIODIC, TaskRequest(worker = WORKER, periodMillis = 60 * 60 * 1000L), ExistingPolicy.Keep)
    }

    companion object {
        const val WORKER = "import-audio-cleanup"
        const val PERIODIC = "import-audio-cleanup-periodic"
    }
}
