package app.resound.platform.scheduler

/** 约束：网络。桌面无法区分是否计流量，[Unmetered] 与 [Any] 在实现里等价（见任务卡 002）。 */
enum class NetworkRequirement { None, Any, Unmetered }

enum class BackoffPolicy { Linear, Exponential }

/** 与 WorkManager 一致：默认指数退避，起始 30 秒，上限 5 小时。 */
data class Backoff(val policy: BackoffPolicy = BackoffPolicy.Exponential, val delayMillis: Long = 30_000) {
    /** [attempt] 为已失败的次数（≥1）。 */
    fun delayFor(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        val raw = when (policy) {
            BackoffPolicy.Linear -> delayMillis * n
            BackoffPolicy.Exponential -> delayMillis * (1L shl (n - 1).coerceAtMost(20))
        }
        return raw.coerceAtMost(MAX_MILLIS)
    }

    companion object { const val MAX_MILLIS = 5 * 60 * 60 * 1000L }
}

/** 输入数据只允许 String/Int/Long/Boolean，保证能序列化落盘。 */
typealias TaskInput = Map<String, Any?>

data class TaskRequest(
    /** 已注册的任务类型 id，对应安卓的 Worker 类。 */
    val worker: String,
    val input: TaskInput = emptyMap(),
    val initialDelayMillis: Long = 0,
    val network: NetworkRequirement = NetworkRequirement.None,
    val backoff: Backoff = Backoff(),
    val tags: Set<String> = emptySet(),
    /** 周期任务的间隔；null 为一次性任务。 */
    val periodMillis: Long? = null,
    /** 加急：同时到期时优先于普通任务。 */
    val expedited: Boolean = false,
)

/** 同名任务已存在时的处理，语义同 WorkManager 的 ExistingWorkPolicy / ExistingPeriodicWorkPolicy。 */
enum class ExistingPolicy {
    /** 已有未完成的同名任务就忽略新的。 */
    Keep,

    /** 取消旧的，用新的。 */
    Replace,

    /** 排在旧任务之后；旧任务失败则被替换。仅一次性任务。 */
    AppendOrReplace,

    /** 周期任务：保留已排的下次时间，只更新参数与间隔。 */
    Update,
}

enum class TaskState { Enqueued, Running, Succeeded, Failed, Cancelled, Blocked }

data class TaskInfo(
    val name: String,
    val worker: String,
    val state: TaskState,
    val attempts: Int,
    val nextRunAt: Long,
    val tags: Set<String>,
    val periodic: Boolean,
)

sealed interface TaskResult {
    data object Success : TaskResult
    data object Failure : TaskResult
    data object Retry : TaskResult
}

/** 任务执行时的上下文。 */
interface TaskContext {
    val name: String
    val input: TaskInput
    val attempt: Int
    val isCancelled: Boolean
    val tags: Set<String>
}

typealias TaskWorker = suspend (TaskContext) -> TaskResult