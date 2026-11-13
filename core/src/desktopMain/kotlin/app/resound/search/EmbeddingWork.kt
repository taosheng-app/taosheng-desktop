package app.resound.search

import app.resound.data.AppDirs
import app.resound.data.CaptureDatabase
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.NetworkRequirement
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Desktop wiring for search model download and local embedding tasks. */
object EmbeddingScheduler {
    const val MODEL_WORKER = "search-model-download"
    const val EMBEDDING_WORKER = "search-embedding"
    private const val MODEL_TASK = "search-model"
    private const val EMBEDDING_TASK = "search-embeddings"
    private const val CATCHUP_TASK = "search-embedding-catchup"

    fun start(scheduler: Scheduler, dirs: AppDirs, database: CaptureDatabase) {
        SearchModelWork(dirs, scheduler).register(scheduler)
        EmbeddingWork(dirs, database, scheduler).register(scheduler)
        scheduler.enqueuePeriodic(CATCHUP_TASK, TaskRequest(worker = EMBEDDING_WORKER, periodMillis = 15 * 60_000L), ExistingPolicy.Keep)
        scheduler.enqueueUnique(MODEL_TASK, TaskRequest(worker = MODEL_WORKER, network = NetworkRequirement.Any), ExistingPolicy.Keep)
        enqueue(scheduler)
    }

    fun enqueue(scheduler: Scheduler, continuation: Boolean = false) {
        scheduler.enqueueUnique(EMBEDDING_TASK, TaskRequest(worker = EMBEDDING_WORKER),
            if (continuation) ExistingPolicy.AppendOrReplace else ExistingPolicy.Keep)
    }

    /** Desktop workloads are not gated on battery state. */
    fun powerAllowed(): Boolean = true
}

class SearchModelWork(private val dirs: AppDirs, private val scheduler: Scheduler) {
    suspend fun doWork(@Suppress("UNUSED_PARAMETER") context: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        try {
            SearchModels.download(dirs)
            EmbeddingScheduler.enqueue(scheduler) // 首次就绪补算全部历史。
            TaskResult.Success
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TaskResult.Retry }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(EmbeddingScheduler.MODEL_WORKER, ::doWork)
}

class EmbeddingWork(
    private val dirs: AppDirs,
    private val database: CaptureDatabase,
    private val scheduler: Scheduler,
) {
    suspend fun doWork(context: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        if (context.input["kickOnly"] == true) {
            EmbeddingScheduler.enqueue(scheduler)
            return@withContext TaskResult.Success
        }
        if (!SearchModels.ready(dirs)) return@withContext TaskResult.Success
        if (!EmbeddingScheduler.powerAllowed()) return@withContext TaskResult.Retry
        val store = EmbeddingStore(database)
        try {
            LocalEmbedder(dirs).use { embedder ->
                // One batch per task; enqueue remaining work so successful batches do not incur backoff.
                val batch = store.pending(embedder.model)
                for (document in batch) {
                    coroutineContext.ensureActive()
                    if (!EmbeddingScheduler.powerAllowed()) return@withContext TaskResult.Retry
                    store.save(document, embedder.model, embedder.embed(document.text))
                }
                IdeaMerger(database).merge(embedder.model)
                if (store.pending(embedder.model).isNotEmpty()) EmbeddingScheduler.enqueue(scheduler, continuation = true)
                TaskResult.Success
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TaskResult.Retry }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(EmbeddingScheduler.EMBEDDING_WORKER, ::doWork)
}
