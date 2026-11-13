package app.resound.transcribe

import app.resound.PipelineProgress
import app.resound.PipelineProgress.Stage
import app.resound.data.TranscribeDao
import app.resound.digest.DigestAlarm
import app.resound.digest.DigestMode
import app.resound.digest.DigestRunner
import app.resound.digest.DigestTime
import app.resound.digest.DigestTiming
import app.resound.formatElapsed
import app.resound.platform.NetworkMonitor
import app.resound.platform.scheduler.Backoff
import app.resound.platform.scheduler.BackoffPolicy
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.NetworkRequirement
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskInfo
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import app.resound.platform.scheduler.TaskState
import app.resound.voiceprint.LocalVoiceprintRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 转写 → 认人 → 整理流水线（安卓 `TranscribeWork`）。
 * 桌面版：WorkManager 的 CoroutineWorker 变成注册到 [Scheduler] 的任务函数；
 * 不再有 ResoundApp/AppGraph 全局，所需对象全部由装配层构造时传入；
 * 没有前台服务/加急配额，也没有电量与流量约束（见 [EarlyTranscribe]、[NetworkMonitor]）。
 */
class TranscribeWork(
    private val settings: TranscribeSettings,
    private val dao: TranscribeDao,
    private val transcribeRunner: TranscribeRunner,
    private val voiceprint: LocalVoiceprintRepository,
    private val digestRunner: DigestRunner,
    private val network: NetworkMonitor,
    /** 到点整理的目标分钟是否仍在当前设置里（设置改过就作废）。 */
    private val timedDigestValid: (target: Long) -> Boolean,
    /** 到点任务开始时接续下一个闹钟（安卓版的 app.rescheduleDigest）。 */
    private val onTimedFired: () -> Unit = {},
    /** 整理后顺便查余额（最多每小时一次），失败忽略。 */
    private val refreshBalance: (suspend () -> Unit)? = null,
    /** 能力：设置是否选了实时转写。 */
    private val realtimeEnabled: () -> Boolean = { (settings as? AndroidTranscribeSettings)?.realtime?.value == true },
    private val powerDirectory: java.io.File? = null,
) {
    suspend fun doWork(ctx: TaskContext): TaskResult {
        val input = ctx.input
        val mode = input[MODE] as? String ?: MODE_FULL
        if (mode == MODE_REALTIME) {
            if (!realtimeEnabled()) return TaskResult.Success
            powerDirectory?.let { TranscribePowerDiagnostics.execution(it, input[BATCHED] == true) }
            BackgroundTranscribeDiagnostics.delayed(input)
        }
        val target = (input[DigestAlarm.TARGET] as? Number)?.toLong() ?: 0L
        val expires = (input[DigestAlarm.EXPIRES] as? Number)?.toLong() ?: 0L
        fun expired(): Boolean {
            if (mode != MODE_TIMED) return false
            return !timedDigestValid(target) || DigestTime.missed(System.currentTimeMillis(), target, expires)
        }
        if (mode == MODE_TIMED) onTimedFired()
        if (expired()) { missed(); return TaskResult.Success }
        // 周期、手动、到点与回顾前整理共用同一把锁。
        if (!pipeline.tryLock()) {
            if (mode == MODE_REALTIME && !BackgroundTranscribeDiagnostics.foreground)
                BackgroundTranscribeDiagnostics.record("realtime-pipeline-busy", "false")
            return TaskResult.Retry
        }
        try {
            if (expired()) { missed(); return TaskResult.Success }
            val retry = executePipeline(mode, manual = mode == MODE_FULL && MANUAL_DIGEST in ctx.tags)
            // 到点任务本轮失败不补跑；普通转写/间隔整理保持原来的重试行为。
            return if (retry && mode != MODE_TIMED) TaskResult.Retry else TaskResult.Success
        } catch (e: CancellationException) {
            if (mode == MODE_REALTIME && !BackgroundTranscribeDiagnostics.foreground)
                BackgroundTranscribeDiagnostics.record("realtime-stopped", "false")
            throw e
        } finally { PipelineProgress.finish(); pipeline.unlock() }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(WORKER, ::doWork)

    private fun missed() = PipelineTimes.record("${System.currentTimeMillis()} 到点整理 已错过，跳过")

    /** 回顾前整理：调用方的 10 分钟超时包含排队等锁，拿到锁后才操作全局进度。 */
    suspend fun beforeReview() = pipeline.withLock {
        try { executePipeline(MODE_REVIEW) } finally { PipelineProgress.finish() }
    }

    private suspend fun executePipeline(mode: String, manual: Boolean = false): Boolean {
        var mark = System.nanoTime() / 1_000_000
        fun lap(): Long = (System.nanoTime() / 1_000_000).let { now -> (now - mark).also { mark = now } }
        val pendingBefore = dao.pendingMillis()
        val parts = mutableListOf<String>()
        var transcribeRetry = false
        var voiceprintRetry = false
        val skip = if (mode == MODE_DIGEST) {
            digestTranscribeSkip(
                (settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local,
                settings.wifiOnly.value, network.isOnline(), // 桌面无法区分计流量网络，视为不计流量
                EarlyTranscribe.deviceAllows(realtime = true, pendingMillis = pendingBefore), pendingBefore)
        } else null
        if (skip != null) parts += "跳过转写：$skip"
        var digestRetry = false
        runPipelineStages(mode, skip, transcribe = {
            PipelineProgress.stage(Stage.Transcribe)
            // 到点/回顾前与“现在整理”一样，把此前失败、正在退避的片段也补转一次。
            if (mode == MODE_TIMED || mode == MODE_REVIEW) dao.retryFailed()
            transcribeRetry = transcribeRunner.run()
            parts += "转写 ${formatElapsed(lap())}"
            if (voiceprint.hasPending()) {
                PipelineProgress.stage(Stage.Voiceprint)
                voiceprintRetry = voiceprint.run(PipelineProgress::progress)
            }
            voiceprint.cleanupAudio()
            parts += "声纹 ${formatElapsed(lap())}"
        }, organize = {
            PipelineProgress.stage(Stage.Digest)
            digestRetry = voiceprint.withStableResults {
                digestRunner.run(force = manual || mode == MODE_TIMED || mode == MODE_REVIEW, manual = manual)
            }
            voiceprint.cleanupAudio()
            parts += "整理 ${formatElapsed(lap())}"
            refreshBalance?.let { runCatching { it() } }
        })
        // 回顾前整理由外层统一记一次诊断（含等锁、超时和失败）。
        if (mode != MODE_REVIEW) PipelineTimes.record("${System.currentTimeMillis()} ${MODE_NAMES[mode] ?: mode} 待转录音 ${formatElapsed(pendingBefore)} · " + parts.joinToString(" · "))
        return transcribeRetry || voiceprintRetry || digestRetry
    }

    companion object {
        /** 调度器里的任务类型 id（对应安卓 Worker 类）。 */
        const val WORKER = "transcribe-work"
        private val pipeline = Mutex()
        const val MODE = "mode"
        const val MODE_REALTIME = "realtime"
        internal const val BATCHED = "earlyBatched"
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_DIGEST = "digest"
        const val MODE_FULL = "full"
        /** 作为调度器 tag 传入，表示用户点了“现在整理”。 */
        const val MANUAL_DIGEST = "digest-manual"
        const val MODE_TIMED = "timed"
        private const val MODE_REVIEW = "review"
        private val MODE_NAMES = mapOf(MODE_REALTIME to "实时转写", MODE_TRANSCRIBE to "定时转写", MODE_DIGEST to "定时整理",
            MODE_FULL to "现在整理", MODE_TIMED to "到点整理", MODE_REVIEW to "回顾前整理")
    }
}

/** 排任务：对应安卓 `TranscribeScheduler`，WorkManager → [Scheduler]。 */
class TranscribeScheduler(
    private val scheduler: Scheduler,
    private val settings: TranscribeSettings,
    private val network: NetworkMonitor,
) {
    /** 多久整理一次；由装配层在整理设置就绪后设置 */
    var digestMinutes: () -> Int = { 30 }
    var digestTiming: DigestTiming? = null
    /** 到点整理模式下，排下一个时间点的任务；由装配层赋值为 DigestAlarm::schedule / ::cancel */
    var timedAlarm: DigestAlarm? = null
    private val realtime get() = (settings as? AndroidTranscribeSettings)?.realtime?.value == true
    private val local get() = (settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local

    /** 整理要调大模型，只要求联网（与转写的 WiFi 设置无关） */
    private fun digestNetwork() = NetworkRequirement.Any
    private fun networkRequirement() = when {
        local -> NetworkRequirement.None
        settings.wifiOnly.value -> NetworkRequirement.Unmetered
        else -> NetworkRequirement.Any
    }

    /** 定时转写和定时整理各排各的。选了实时转写时，定时转写只做兜底。 */
    fun schedule() {
        scheduleDigest()
        val minutes = if (realtime) REALTIME_FALLBACK_MINUTES else settings.intervalMinutes.value.toLong()
        scheduler.enqueuePeriodic(PERIODIC, TaskRequest(
            worker = TranscribeWork.WORKER, input = mapOf(TranscribeWork.MODE to TranscribeWork.MODE_TRANSCRIBE),
            initialDelayMillis = minutes * 60_000L, periodMillis = minutes * 60_000L,
            network = networkRequirement(), tags = setOf(TAG),
            backoff = Backoff(BackoffPolicy.Exponential, 60_000L)), ExistingPolicy.Update)
    }

    /** 用户主动触发：旧的立即任务在等退避重试就替换掉马上跑；正在执行则追加一次后续运行。 */
    fun runNow() = enqueueNow(manualDigest = false)
    fun runDigestNow() = enqueueNow(manualDigest = true)
    private fun enqueueNow(manualDigest: Boolean) {
        PipelineProgress.queued(waitingReason() ?: "马上开始…")
        val running = scheduler.status(IMMEDIATE)?.state == TaskState.Running
        scheduler.enqueueUnique(IMMEDIATE, TaskRequest(
            worker = TranscribeWork.WORKER, input = mapOf(TranscribeWork.MODE to TranscribeWork.MODE_FULL),
            network = networkRequirement(),
            tags = setOf(TAG) + if (manualDigest) setOf(TranscribeWork.MANUAL_DIGEST) else emptySet(),
            expedited = true, backoff = Backoff(BackoffPolicy.Exponential, 60_000L)),
            if (running) ExistingPolicy.AppendOrReplace else ExistingPolicy.Replace)
    }

    /** 点了马上跑但条件不满足时，给人看的原因；满足返回 null */
    private fun waitingReason(): String? {
        if (local) return null
        if (!network.isOnline()) return "等网络连上后开始"
        return null
    }

    /** 设置改变后重排周期任务；已排队的立即任务在下一轮 tick 按最新约束重新评估不到，这里直接重建。 */
    fun settingsChanged() {
        schedule()
        val now = scheduler.status(IMMEDIATE)
        if (now?.state == TaskState.Enqueued) {
            val manual = TranscribeWork.MANUAL_DIGEST in now.tags
            enqueueNowKeepingProgress(manual)
        }
    }
    private fun enqueueNowKeepingProgress(manualDigest: Boolean) {
        scheduler.enqueueUnique(IMMEDIATE, TaskRequest(
            worker = TranscribeWork.WORKER, input = mapOf(TranscribeWork.MODE to TranscribeWork.MODE_FULL),
            network = networkRequirement(),
            tags = setOf(TAG) + if (manualDigest) setOf(TranscribeWork.MANUAL_DIGEST) else emptySet(),
            expedited = true, backoff = Backoff(BackoffPolicy.Exponential, 60_000L)), ExistingPolicy.Replace)
    }

    fun scheduleDigest() {
        digestTiming?.let { timing ->
            val alarm = timedAlarm
            if (timing.mode.value == DigestMode.TimePoints) {
                scheduler.cancel(DIGEST_PERIODIC)
                alarm?.schedule()
                return
            }
            alarm?.cancel()
        }
        val minutes = digestMinutes().toLong()
        scheduler.enqueuePeriodic(DIGEST_PERIODIC, TaskRequest(
            worker = TranscribeWork.WORKER, input = mapOf(TranscribeWork.MODE to TranscribeWork.MODE_DIGEST),
            initialDelayMillis = minutes * 60_000L, periodMillis = minutes * 60_000L,
            network = digestNetwork(), tags = setOf(TAG)), ExistingPolicy.Update)
    }

    /** 到点整理任务（由 [DigestAlarm] 调用）；只在“按时间点”模式下排。 */
    fun enqueueTimed(target: Long, expires: Long, delayMillis: Long): Boolean {
        if (digestTiming?.mode?.value != DigestMode.TimePoints) return false
        scheduler.enqueueUnique("digest-timed-$target", TaskRequest(
            worker = TranscribeWork.WORKER,
            input = mapOf(TranscribeWork.MODE to TranscribeWork.MODE_TIMED, DigestAlarm.TARGET to target, DigestAlarm.EXPIRES to expires),
            initialDelayMillis = delayMillis.coerceAtLeast(0), network = digestNetwork(), tags = setOf(TAG, TIMED_TAG), expedited = true),
            ExistingPolicy.Keep)
        return true
    }

    fun cancelTimed() { scheduler.cancelByTag(TIMED_TAG) }

    /** 设置/系统时钟改变后旧窗口失效；进程启动和接续不取消已触发的任务。 */
    fun timingChanged() { scheduler.cancelByTag(TIMED_TAG); scheduleDigest() }

    /** 每存好一段录音调用。桌面始终接电，立即转（不攒批）。 */
    suspend fun segmentSaved(pendingMillis: Long) = earlyMutex.withLock {
        if (!settings.ready.value || !EarlyTranscribe.deviceAllows(realtime, pendingMillis)) return@withLock
        val running = scheduler.status(EARLY_WORK)?.state == TaskState.Running
        scheduler.enqueueUnique(EARLY_WORK, TaskRequest(
            worker = TranscribeWork.WORKER,
            input = BackgroundTranscribeDiagnostics.input() + (TranscribeWork.BATCHED to false),
            network = networkRequirement(), tags = setOf(TAG), expedited = true,
            backoff = Backoff(BackoffPolicy.Linear, 30_000L)),
            if (running) ExistingPolicy.AppendOrReplace else ExistingPolicy.Keep)
    }

    fun observe(): Flow<List<TaskInfo>> = scheduler.observeByTag(TAG)

    companion object {
        private val earlyMutex = Mutex()
        const val TAG = "transcribe"
        const val TIMED_TAG = "digest-timed"
        const val PERIODIC = "transcribe-periodic"
        const val IMMEDIATE = "transcribe-now"
        const val EARLY_WORK = "transcribe-early"
        const val DIGEST_PERIODIC = "digest-periodic"
        const val REALTIME_FALLBACK_MINUTES = 30L
    }
}

/** 间隔整理的前置转写遵守自身条件，整理仍可继续。 */
internal fun digestTranscribeSkip(local: Boolean, wifiOnly: Boolean, unmetered: Boolean,
                                  deviceAllows: Boolean, pendingMillis: Long): String? = when {
    !local && wifiOnly && !unmetered -> "只在 WiFi 下转写，当前不是不计流量的网络"
    pendingMillis <= 0 -> "没有待转写片段"
    !deviceAllows -> "电量低或手机偏热"
    else -> null
}

internal suspend fun runPipelineStages(mode: String, skip: String?,
                                       transcribe: suspend () -> Unit, organize: suspend () -> Unit) {
    if (mode != TranscribeWork.MODE_DIGEST || skip == null) transcribe()
    if (mode in setOf(TranscribeWork.MODE_FULL, TranscribeWork.MODE_DIGEST, TranscribeWork.MODE_TIMED, "review")) organize()
}
