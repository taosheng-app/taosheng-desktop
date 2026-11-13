package app.resound.privacy

import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult

/**
 * 即使删除后进程退出，待清理路径仍在数据库；启动和周期任务均可续做。
 * 桌面版：安卓的 WorkManager 换成 platform 的 [Scheduler]；Worker 类变成注册到调度器的任务函数。
 */
class PrivacyCleanupWorker(private val actions: () -> RoomPrivacyActions) {
    suspend fun doWork(@Suppress("UNUSED_PARAMETER") context: TaskContext? = null): TaskResult = try {
        if (actions().retryFileCleanup()) TaskResult.Success else TaskResult.Retry
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { TaskResult.Retry }

    /** 把任务类型注册到调度器；必须在 [start]/[enqueue] 之前调用。 */
    fun register(scheduler: Scheduler) = scheduler.registerWorker(WORKER, ::doWork)

    companion object {
        const val WORKER = "privacy-file-cleanup"
        const val ONCE = "privacy-file-cleanup"
        const val PERIODIC = "privacy-file-cleanup-catchup"

        fun enqueue(scheduler: Scheduler) {
            scheduler.enqueueUnique(ONCE, TaskRequest(worker = WORKER), ExistingPolicy.Keep)
        }

        fun start(scheduler: Scheduler) {
            scheduler.enqueuePeriodic(PERIODIC, TaskRequest(worker = WORKER, periodMillis = 15 * 60_000L), ExistingPolicy.Keep)
            enqueue(scheduler)
        }
    }
}
