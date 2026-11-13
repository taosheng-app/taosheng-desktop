package app.resound.transcribe

import app.resound.platform.Channels
import app.resound.platform.DesktopNotifier
import app.resound.platform.NotificationEvent
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskResult
import kotlinx.coroutines.CancellationException

/** Desktop scheduler task for a persisted export. */
internal class AudioExportWorker(
    private val jobs: AndroidExportJobs,
    private val notifier: DesktopNotifier? = null,
) {
    suspend fun doWork(ctx: TaskContext): TaskResult {
        val jobId = ctx.input[INPUT_EXPORT_ID] as? String ?: return TaskResult.Failure
        val job = jobs.job(jobId) ?: return TaskResult.Success
        if (job.state !is ExportJobState.Running) return TaskResult.Success
        return try {
            val success = jobs.run(jobId) { update ->
                notifier?.send(NotificationEvent(Channels.RecordingPack,
                    "正在导出 · ${update.title} · ${(((update.state as? ExportJobState.Running)?.fraction ?: 0f) * 100).toInt()}%", "", tag = jobId))
            }
            jobs.job(jobId)?.let(::finished)
            if (success) TaskResult.Success else TaskResult.Failure
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TaskResult.Retry }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(WORKER, ::doWork)

    private fun finished(job: ExportJob) {
        when (val state = job.state) {
            is ExportJobState.Done -> notifier?.send(NotificationEvent(Channels.RecordingPack, "录音已导出 · ${job.title}", "点此试听、保存或分享", tag = job.id))
            is ExportJobState.Failed -> notifier?.send(NotificationEvent(Channels.RecordingPack, "录音导出失败 · ${job.title}", state.message, tag = job.id))
            else -> Unit
        }
    }

    companion object {
        const val WORKER = "audio-export"
        const val INPUT_EXPORT_ID = "export-id"
    }
}
