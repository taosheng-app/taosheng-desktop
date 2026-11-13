package app.resound.platform.scheduler

import app.resound.platform.Clock
import app.resound.platform.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 进程内任务调度器，API 形状贴近 WorkManager（任务卡 002）。
 *
 * - 任务按 [name] 唯一；状态持久化到 [stateFile]，重启后恢复，到期立即补跑；**停机错过的周期任务只补跑一次**。
 * - [tick] 找出到期且满足约束的任务并执行，生产环境由 [start] 的后台循环定时调用，测试可直接调用并用假 [Clock]。
 * - 同名任务不并发；全局并发上限 [maxConcurrency]。
 */
class Scheduler(
    stateFile: File,
    private val clock: Clock = Clock.System,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val maxConcurrency: Int = 4,
    private val tickMillis: Long = 1_000,
) {
    private val store = TaskStore(stateFile)
    private val workers = HashMap<String, TaskWorker>()
    private val running = HashMap<String, Job>()
    private val cancelled = HashSet<String>()
    private val gate = Semaphore(maxConcurrency)
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    private var loop: Job? = null

    fun registerWorker(id: String, worker: TaskWorker) {
        synchronized(lock) { workers[id] = worker }
    }

    /** 启动后台循环。恢复上次未完成的任务：被打断的 Running 视为 Enqueued 重新排队。 */
    fun start() {
        synchronized(lock) {
            store.recoverAfterRestart(clock.now())
            if (loop != null) return
            loop = scope.launch {
                while (true) {
                    tick()
                    delay(tickMillis)
                }
            }
        }
        changed()
    }

    fun stop() { synchronized(lock) { loop?.cancel(); loop = null } }

    fun enqueueUnique(name: String, request: TaskRequest, policy: ExistingPolicy = ExistingPolicy.Keep) {
        require(request.periodMillis == null) { "周期任务请用 enqueuePeriodic" }
        require(policy != ExistingPolicy.Update) { "Update 只用于周期任务" }
        require(request.input.values.all { it == null || it is String || it is Int || it is Long || it is Boolean }) { "输入只允许 String/Int/Long/Boolean" }
        synchronized(lock) {
            val existing = store.get(name)
            val live = existing != null && existing.state in LIVE
            when {
                live && policy == ExistingPolicy.Keep -> return
                live && policy == ExistingPolicy.Replace -> cancelLocked(name)
                live && policy == ExistingPolicy.AppendOrReplace -> {
                    // 旧任务还没结束：把新任务挂在它后面（接到旧任务之后执行）；旧任务失败时由 finish 逻辑改为直接替换
                    store.put(existing!!.copy(pending = Record.from(name, request, clock.now())))
                    changed(); return
                }
            }
            store.put(Record.from(name, request, clock.now()))
        }
        changed()
    }

    fun enqueuePeriodic(name: String, request: TaskRequest, policy: ExistingPolicy = ExistingPolicy.Keep) {
        val period = requireNotNull(request.periodMillis) { "周期任务需要 periodMillis" }
        require(period >= MIN_PERIOD_MILLIS) { "周期不能小于 15 分钟" }
        require(policy == ExistingPolicy.Keep || policy == ExistingPolicy.Update || policy == ExistingPolicy.Replace) { "周期任务不支持 $policy" }
        synchronized(lock) {
            val existing = store.get(name)
            val live = existing != null && existing.state in LIVE
            when {
                live && policy == ExistingPolicy.Keep -> return
                live && policy == ExistingPolicy.Update -> {
                    // 保留已排的下次时间，更新参数；间隔变短时不晚于新间隔
                    val next = minOf(existing!!.nextRunAt, clock.now() + period)
                    store.put(Record.from(name, request, clock.now()).copy(nextRunAt = next, attempts = 0))
                    changed(); return
                }
                live && policy == ExistingPolicy.Replace -> cancelLocked(name)
            }
            // 首次排队：首个周期之后才运行（同 WorkManager 默认）；有 initialDelay 则按它
            val first = clock.now() + (if (request.initialDelayMillis > 0) request.initialDelayMillis else period)
            store.put(Record.from(name, request, clock.now()).copy(nextRunAt = first))
        }
        changed()
    }

    fun cancel(name: String) { synchronized(lock) { cancelLocked(name) }; changed() }

    fun cancelByTag(tag: String) {
        synchronized(lock) { store.all().filter { tag in it.tags && it.state in LIVE }.forEach { cancelLocked(it.name) } }
        changed()
    }

    fun status(name: String): TaskInfo? = synchronized(lock) { store.get(name)?.info() }

    fun statusByTag(tag: String): List<TaskInfo> = synchronized(lock) { store.all().filter { tag in it.tags }.map { it.info() } }

    fun observe(name: String): Flow<TaskInfo?> = changes.map { status(name) }

    /**
     * 执行所有到期且满足约束的任务一轮，返回本轮启动的任务数。
     * 测试里先推进假时钟再调用；调用本身会等本轮任务全部完成。
     */
    suspend fun tick(): Int {
        val due = synchronized(lock) {
            val now = clock.now()
            val online = network.isOnline()
            store.all()
                .filter { it.state == TaskState.Enqueued && it.nextRunAt <= now && it.name !in running }
                .filter { it.network == NetworkRequirement.None || online }
                .sortedWith(compareByDescending<Record> { it.expedited }.thenBy { it.nextRunAt })
                .onEach { rec ->
                    store.put(rec.copy(state = TaskState.Running, attempts = rec.attempts + 1))
                }
                .map { it.name }
        }
        if (due.isEmpty()) return 0
        changed()
        val jobs = due.map { name -> scope.launch { gate.withPermit { execute(name) } }.also { job -> synchronized(lock) { running[name] = job } } }
        jobs.forEach { it.join() }
        return due.size
    }

    private suspend fun execute(name: String) {
        val rec = synchronized(lock) { store.get(name) } ?: return
        val worker = synchronized(lock) { workers[rec.worker] }
        val result = if (worker == null) TaskResult.Failure else try {
            worker(object : TaskContext {
                override val name = rec.name
                override val input = rec.input
                override val attempt = rec.attempts
                override val isCancelled get() = synchronized(lock) { name in cancelled }
                override val tags = rec.tags
            })
        } catch (e: CancellationException) {
            synchronized(lock) { running.remove(name) }
            finishCancelled(name)
            throw e
        } catch (e: Throwable) {
            TaskResult.Failure
        }
        synchronized(lock) { running.remove(name) }
        finish(name, result)
    }

    private fun finishCancelled(name: String) = synchronized(lock) {
        store.get(name)?.let { store.put(it.copy(state = TaskState.Cancelled, pending = null)) }
        cancelled.remove(name)
    }.also { changed() }

    private fun finish(name: String, result: TaskResult) {
        synchronized(lock) {
            val rec = store.get(name) ?: return
            if (name in cancelled) { cancelled.remove(name); store.put(rec.copy(state = TaskState.Cancelled, pending = null)); return@synchronized }
            val now = clock.now()
            val next: Record = when {
                result == TaskResult.Retry -> rec.copy(state = TaskState.Enqueued, nextRunAt = now + rec.backoff().delayFor(rec.attempts))
                rec.periodMillis != null -> {
                    // 周期任务：成功或失败都排下一个周期；重试已在上一分支处理
                    rec.copy(state = TaskState.Enqueued, attempts = 0, nextRunAt = now + rec.periodMillis)
                }
                result == TaskResult.Success -> rec.pending?.copy(state = TaskState.Enqueued) ?: rec.copy(state = TaskState.Succeeded, pending = null)
                else -> rec.pending?.copy(state = TaskState.Enqueued) ?: rec.copy(state = TaskState.Failed, pending = null)
            }
            store.put(next)
        }
        changed()
    }

    private fun cancelLocked(name: String) {
        val rec = store.get(name) ?: return
        if (rec.state == TaskState.Running) {
            cancelled += name
            running[name]?.cancel()
        } else if (rec.state in LIVE) store.put(rec.copy(state = TaskState.Cancelled, pending = null))
    }

    private fun changed() { changes.value = changes.value + 1 }

    companion object {
        const val MIN_PERIOD_MILLIS = 15 * 60 * 1000L
        private val LIVE = setOf(TaskState.Enqueued, TaskState.Running, TaskState.Blocked)
    }
}