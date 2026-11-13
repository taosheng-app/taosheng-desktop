package app.resound.voiceprint

import app.resound.platform.scheduler.Backoff
import app.resound.platform.scheduler.BackoffPolicy
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import kotlinx.coroutines.CancellationException

/** Independent local recomputation task, registered with the persistent desktop scheduler. */
class VoiceprintRecomputeWork(private val repository: LocalVoiceprintRepository) {
    suspend fun doWork(context: TaskContext): TaskResult {
        val days = context.input[DAYS] as? Int ?: 7
        if (days !in 1..30) return TaskResult.Failure
        return try {
            if (repository.recomputeRecent(days)) {
                if (context.attempt < 3) TaskResult.Retry else TaskResult.Failure
            } else TaskResult.Success
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (context.attempt < 3) TaskResult.Retry else TaskResult.Failure }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(WORKER, ::doWork)

    companion object {
        const val NAME = "voiceprint-recompute-v2"
        const val WORKER = NAME
        private const val DAYS = "days"

        /** Repeated requests append, preserving the user's later day ranges. */
        fun enqueue(scheduler: Scheduler, days: Int = 7) {
            require(days in 1..30)
            scheduler.enqueueUnique(NAME, TaskRequest(
                worker = WORKER,
                input = mapOf(DAYS to days),
                backoff = Backoff(BackoffPolicy.Exponential, 30_000L),
            ), ExistingPolicy.AppendOrReplace)
        }
    }
}
