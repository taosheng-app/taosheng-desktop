package app.resound.topic

import app.resound.data.CaptureDatabase
import app.resound.digest.AndroidDigestSettings
import app.resound.digest.DigestPrompt
import app.resound.importer.ImportKind
import app.resound.search.EmbeddingScheduler
import app.resound.digest.LlmClient
import app.resound.digest.LlmError
import app.resound.digest.LlmException
import app.resound.digest.LlmReply
import app.resound.platform.Channels
import app.resound.platform.DesktopNotifier
import app.resound.platform.NotificationEvent
import app.resound.platform.scheduler.Backoff
import app.resound.platform.scheduler.BackoffPolicy
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.NetworkRequirement
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskContext
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import app.resound.skills.Skill
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TopicFeature(
    val repository: RoomTopicRepository,
    val engine: TopicEngine,
    private val notifier: TopicNotifier,
) {
    suspend fun action(action: TopicAction, topicId: Long = 0L) {
        if (topicId != 0L && repository.current.value?.id != topicId) return
        when (action) {
            TopicAction.Start -> {
                val result = repository.start(TopicKind.Meeting, null)
                when {
                    result.error != null -> throw TopicException(result.error)
                    result.recordingDisabled -> notifier.show(result.topicId!!, TopicReminder.RecordingOff, scheduled = false)
                    result.quietOverride -> notifier.error("专题已开始，这段时间原本设为不录音")
                }
            }
            TopicAction.Mark -> repository.mark()
            TopicAction.Pause -> repository.pause()
            TopicAction.Resume -> repository.resume()
            TopicAction.End -> repository.end()
            TopicAction.Extend -> repository.extend(30)
        }
    }

    companion object {
        fun create(
            db: CaptureDatabase,
            scope: CoroutineScope,
            settings: AndroidDigestSettings,
            llm: LlmClient,
            hotwords: () -> List<String>,
            digestPrompt: () -> String,
            importTemplate: (ImportKind) -> Pair<Skill, String>,
            quiet: (Long, Long) -> Boolean,
            recordingEnabled: () -> Boolean,
            voiceprintStableResults: suspend (suspend () -> Boolean) -> Boolean,
            voiceprintReady: suspend () -> Boolean,
            recordingPending: (Long, Long) -> Boolean,
            notifier: TopicNotifier,
            scheduler: Scheduler,
            runTranscription: suspend () -> Unit = {},
        ): TopicFeature {
            val repository = RoomTopicRepository(db, scope, quiet = quiet, recordingEnabled = recordingEnabled)
            val generator = TopicGenerator(digestPrompt, importTemplate, request = { system, user ->
                val (preset, key) = settings.skillCredentials(null)
                if (key.isBlank() && !settings.canUseWithoutKey(preset) && !settings.failover.hasBackup(preset)) {
                    throw LlmException(LlmError.Auth)
                }
                val reply = settings.failover.request("专题整理", db.memory(), UsagePurpose.Digest, llm::chatJson)(preset, key, system, user)
                withContext(NonCancellable) {
                    db.memory().addUsage(java.time.LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest)
                }
                reply
            }, hotwords)
            val engine = TopicEngine(db, repository, generator, voiceprintReady,
                recordingPending, ready = { id ->
                    scope.launch {
                        db.topics().get(id)?.row?.let { notifier.show(id, TopicReminder.Ready, TopicKind.valueOf(it.kind)) }
                    }
                    EmbeddingScheduler.enqueue(scheduler)
                })
            val task = TopicWork(engine, voiceprintStableResults)
            task.register(scheduler)
            scheduler.enqueuePeriodic(TopicWork.CATCHUP,
                TaskRequest(worker = TopicWork.WORKER, periodMillis = 15 * 60_000L, network = NetworkRequirement.Any), ExistingPolicy.Keep)
            repository.onChanged = {
                notifier.schedule(db.topics().all())
                notifier.clearObsolete(repository.current.value)
            }
            repository.onEnded = { id ->
                runTranscription()
                TopicWork.enqueue(scheduler)
            }
            repository.onReminder = { id, reminder -> notifier.show(id, reminder) }
            scope.launch {
                repository.tick()
                TopicWork.enqueue(scheduler)
                while (true) {
                    delay(60_000)
                    repository.tick()
                    if (db.topics().all().any { it.row.status == "Ended" }) TopicWork.enqueue(scheduler)
                }
            }
            scope.launch {
                db.transcribe().observeSegments()
                    .map { rows -> rows.map { Triple(it.id, it.status, it.voiceprintProcessedAt) } }
                    .distinctUntilChanged().collect {
                        if (db.topics().all().any { it.row.status in setOf("Ended", "Summarizing", "Done") }) {
                            TopicWork.enqueue(scheduler)
                        }
                    }
            }
            val feature = TopicFeature(repository, engine, notifier)
            notifier.onAction = feature::action
            return feature
        }
    }
}

enum class TopicAction { Start, Mark, Pause, Resume, End, Extend }

/** Desktop notification/alarm port. UI code can bind [onAction] to its topic controls. */
internal interface TopicNotifier {
    var onAction: (suspend (TopicAction, Long) -> Unit)?
    fun schedule(groups: List<TopicGroup>)
    fun show(id: Long, reminder: TopicReminder, kind: TopicKind = TopicKind.Meeting, scheduled: Boolean = true)
    fun error(text: String)
    fun clearObsolete(current: Topic?)
}

/** Uses the platform's desktop tray/fallback notification service and an in-process boundary timer. */
internal class DesktopTopicNotifier(
    private val notifier: DesktopNotifier,
    private val scope: CoroutineScope,
    private val onTick: suspend () -> Unit,
) : TopicNotifier {
    override var onAction: (suspend (TopicAction, Long) -> Unit)? = null
    private var alarm: kotlinx.coroutines.Job? = null
    private val activeTags = mutableMapOf<TopicReminder, String>()

    override fun schedule(groups: List<TopicGroup>) {
        alarm?.cancel()
        val now = System.currentTimeMillis()
        val target = groups.flatMap { g -> when (g.row.status) {
            "Scheduled" -> listOfNotNull(g.row.plannedStartAt)
            "Running", "Paused" -> buildList {
                add(g.deadline())
                if (g.row.plannedEndAt != null && g.row.endingNotifiedAt != g.deadline()) add(g.deadline() - 5 * 60_000L)
                if (g.row.status == "Running") add(now + 60_000L)
            }
            else -> emptyList()
        } }.minOrNull() ?: return
        alarm = scope.launch {
            delay((target - System.currentTimeMillis()).coerceAtLeast(1_000L))
            onTick()
        }
    }

    override fun show(id: Long, reminder: TopicReminder, kind: TopicKind, scheduled: Boolean) {
        val title = when (reminder) {
            TopicReminder.Ending -> "还有 5 分钟结束"
            TopicReminder.Quiet -> "15 分钟没人说话，要结束专题吗？"
            TopicReminder.RecordingOff -> if (scheduled) "预约的专题开始了，录音没开" else "专题已开始，录音没开"
            TopicReminder.Ready -> when (kind) {
                TopicKind.Meeting -> "会议纪要好了"; TopicKind.Training -> "培训笔记好了"; TopicKind.Call -> "通话记录好了"
                TopicKind.Script -> "口播稿好了"; TopicKind.Normal -> "专题整理好了"
            }
        }
        val tag = "topic:$id:${reminder.name}"
        activeTags[reminder] = tag
        notifier.send(NotificationEvent(Channels.Import, title, "打开涛声查看专题", deepLink = "topic:$id", tag = tag))
    }

    override fun error(text: String) {
        notifier.send(NotificationEvent(Channels.Interrupted, text, "专题操作失败，请重试", deepLink = "topic", tag = "topic:error"))
    }

    override fun clearObsolete(current: Topic?) {
        val obsolete = if (current == null) setOf(TopicReminder.Ending, TopicReminder.Quiet, TopicReminder.RecordingOff)
            else buildSet {
                if (current.status == TopicStatus.Paused) add(TopicReminder.Quiet)
                if (current.plannedEndAt?.let { it - System.currentTimeMillis() > 5 * 60_000L } == true) add(TopicReminder.Ending)
            }
        obsolete.forEach { reminder -> activeTags.remove(reminder)?.let(notifier::cancel) }
    }
}

internal class TopicWork(
    private val engine: TopicEngine,
    private val stableResults: suspend (suspend () -> Boolean) -> Boolean,
) {
    suspend fun doWork(@Suppress("UNUSED_PARAMETER") context: TaskContext): TaskResult = withContext(Dispatchers.IO) {
        try {
            if (stableResults { engine.run() }) TaskResult.Retry else TaskResult.Success
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TaskResult.Retry }
    }

    fun register(scheduler: Scheduler) = scheduler.registerWorker(WORKER, ::doWork)

    companion object {
        const val WORKER = "topic-summaries"
        const val CATCHUP = "topic-summaries-catchup"
        fun enqueue(scheduler: Scheduler) = scheduler.enqueueUnique("topic-summaries", TaskRequest(
            worker = WORKER,
            network = NetworkRequirement.Any,
            backoff = Backoff(BackoffPolicy.Exponential, 30_000L),
        ), ExistingPolicy.AppendOrReplace)
    }
}

/** Public desktop façade: exposes review/create/generate without leaking internal engine types. */
class DesktopTopicService internal constructor(private val feature: TopicFeature) {
    fun observeDay(date: java.time.LocalDate) = feature.repository.observeDay(date)
    suspend fun previewFromEvents(ids: List<Long>) = feature.repository.previewFromEvents(ids)

    suspend fun createFromEvents(ids: List<Long>, name: String?): Result<Long> {
        val result = feature.repository.createFromEvents(ids, TopicKind.Normal, name)
        return result.fold(
            onSuccess = { id ->
                feature.engine.run()
                Result.success(id)
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun regenerate(topicId: Long) {
        feature.repository.regenerate(topicId)
        feature.engine.run()
    }
}

/** Build only the supported ordinary-topic workflow; specialized Android topic types remain unavailable. */
fun createDesktopTopicService(
    db: CaptureDatabase,
    scope: CoroutineScope,
    settings: AndroidDigestSettings,
    llm: LlmClient,
    notifier: DesktopNotifier,
    scheduler: Scheduler,
    digestPrompt: () -> String,
    hotwords: () -> List<String> = { emptyList() },
): DesktopTopicService {
    lateinit var tick: suspend () -> Unit
    val topicNotifier = DesktopTopicNotifier(notifier, scope) { tick() }
    val feature = TopicFeature.create(
        db = db,
        scope = scope,
        settings = settings,
        llm = llm,
        hotwords = hotwords,
        digestPrompt = digestPrompt,
        importTemplate = { throw UnsupportedOperationException("桌面版暂不支持该专题类型") },
        quiet = { _, _ -> false },
        recordingEnabled = { false },
        voiceprintStableResults = { work -> work() },
        voiceprintReady = { false },
        recordingPending = { _, _ -> false },
        notifier = topicNotifier,
        scheduler = scheduler,
    )
    tick = { feature.repository.tick() }
    return DesktopTopicService(feature)
}
