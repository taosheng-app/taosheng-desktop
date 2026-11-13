package app.resound.transcribe

import app.resound.data.KeyValueStore
import app.resound.platform.DesktopNotifier
import app.resound.platform.scheduler.ExistingPolicy
import app.resound.platform.scheduler.Scheduler
import app.resound.platform.scheduler.TaskRequest
import app.resound.platform.scheduler.TaskResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Persists export requests/results and queues them through the desktop Scheduler. */
internal class AndroidExportJobs(
    private val preferences: KeyValueStore,
    private val exporter: AudioExporter,
    private val scope: CoroutineScope,
    private val scheduler: Scheduler,
    private val notifier: DesktopNotifier? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ExportJobs {
    private data class Entry(val request: ExportRequest, val job: ExportJob, val updated: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val mutableJobs = MutableStateFlow<List<ExportJob>>(emptyList())
    override val jobs: StateFlow<List<ExportJob>> = mutableJobs.asStateFlow()

    init {
        AudioExportWorker(this, notifier).register(scheduler)
        val array = runCatching { JSONArray(preferences.getString("jobs", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) runCatching {
            val json = array.getJSONObject(i)
            val ids = json.getJSONArray("segments")
            val request = ExportRequest((0 until ids.length()).map { ids.getLong(it) }, json.getString("title"),
                includeAudio = json.optBoolean("includeAudio", true),
                textParts = if (json.has("textParts")) json.getJSONArray("textParts").let { parts ->
                    (0 until parts.length()).map { ExportTextPart.valueOf(parts.getString(it)) }.toSet()
                } else if (json.optBoolean("text", false)) setOf(ExportTextPart.Lines) else emptySet(),
                textFormat = ExportTextFormat.valueOf(json.optString("textFormat", ExportTextFormat.Txt.name)),
                includeSrt = json.optBoolean("srt", true), skipMedia = json.optBoolean("media", true))
            val state = when (json.getString("state")) {
                "done" -> ExportJobState.Done(json.getJSONArray("files").let { files -> (0 until files.length()).map { File(files.getString(it)) } })
                "failed" -> ExportJobState.Failed(json.getString("message"))
                "cancelled" -> ExportJobState.Cancelled
                else -> ExportJobState.Running(0f)
            }
            val id = json.getString("id")
            entries[id] = Entry(request, ExportJob(id, request.title, json.getLong("started"), state), json.getLong("updated"))
        }
        prune(); publish()
        entries.values.filter { it.job.state is ExportJobState.Running }.forEach { enqueue(it.job.id) }
        scope.launch { while (isActive) { delay(60_000); synchronized(this@AndroidExportJobs) { prune(); publish() } } }
    }

    @Synchronized override fun start(request: ExportRequest): String {
        prune()
        val normalized = request.copy(segmentIds = request.segmentIds.distinct().sorted())
        entries.values.firstOrNull { it.request == normalized && it.job.state is ExportJobState.Running }?.let { return it.job.id }
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(normalized, ExportJob(id, request.title, now(), ExportJobState.Running(0f)), now())
        publish()
        try { enqueue(id) } catch (_: Exception) { update(id, ExportJobState.Failed("无法安排后台导出，请重试")) }
        return id
    }

    private fun enqueue(id: String) = scheduler.enqueueUnique(WORK_NAME_PREFIX + id,
        TaskRequest(worker = AudioExportWorker.WORKER, input = mapOf(AudioExportWorker.INPUT_EXPORT_ID to id)), ExistingPolicy.Keep)

    @Synchronized override fun cancel(id: String) {
        val entry = entries[id] ?: return
        if (entry.job.state !is ExportJobState.Running) return
        update(id, ExportJobState.Cancelled)
        scheduler.cancel(WORK_NAME_PREFIX + id)
        (exporter as? LocalAudioExporter)?.removeJobFiles(id)
        notifier?.cancel(id)
    }

    @Synchronized override fun dismiss(id: String) {
        if (entries[id]?.job?.state is ExportJobState.Running) return
        entries.remove(id); publish(); notifier?.cancel(id)
    }

    @Synchronized internal fun job(id: String): ExportJob? = entries[id]?.job
    @Synchronized private fun request(id: String): ExportRequest? = entries[id]?.takeIf { it.job.state is ExportJobState.Running }?.request

    @Synchronized internal fun update(id: String, state: ExportJobState): Boolean {
        val old = entries[id] ?: return false
        if (old.job.state !is ExportJobState.Running) return false
        entries[id] = old.copy(job = old.job.copy(state = state), updated = now())
        publish(); return true
    }

    internal suspend fun run(id: String, progress: suspend (ExportJob) -> Unit): Boolean {
        val request = request(id) ?: return job(id)?.state is ExportJobState.Done
        var result: ExportJobState? = null
        var lastProgressAt = 0L
        try {
            val flow = (exporter as? LocalAudioExporter)?.exportIn(request, id) ?: exporter.export(request)
            flow.collect { value ->
                currentCoroutineContext().ensureActive()
                if (job(id)?.state !is ExportJobState.Running) throw CancellationException("导出已取消")
                when (value) {
                    is ExportProgress.Working -> {
                        val at = System.nanoTime() / 1_000_000
                        update(id, ExportJobState.Running(value.fraction))
                        if (lastProgressAt == 0L || at - lastProgressAt >= 1000 || value.fraction == 1f) { lastProgressAt = at; job(id)?.let { progress(it) } }
                    }
                    is ExportProgress.Done -> result = ExportJobState.Done(value.files)
                    is ExportProgress.Failed -> result = ExportJobState.Failed(value.message)
                }
            }
            val terminal = result ?: ExportJobState.Failed("导出未生成结果，请重试")
            return update(id, terminal) && terminal is ExportJobState.Done
        } catch (e: CancellationException) {
            (result as? ExportJobState.Done)?.files?.forEach { it.delete() }
            (exporter as? LocalAudioExporter)?.removeJobFiles(id)
            throw e
        } catch (e: Exception) {
            update(id, ExportJobState.Failed(e.message ?: "导出失败，请重试")); return false
        }
    }

    private fun prune() { entries.entries.removeAll { it.value.job.state !is ExportJobState.Running && it.value.updated < now() - 86_400_000 } }
    private fun publish() {
        val array = JSONArray()
        entries.values.forEach { entry ->
            val r = entry.request; val j = entry.job
            val json = JSONObject().put("id", j.id).put("title", j.title).put("started", j.startedAt).put("updated", entry.updated)
                .put("segments", JSONArray(r.segmentIds)).put("includeAudio", r.includeAudio)
                .put("textParts", JSONArray(ExportTextPart.entries.filter { it in r.textParts }.map { it.name }))
                .put("textFormat", r.textFormat.name).put("srt", r.includeSrt).put("media", r.skipMedia)
            when (val state = j.state) {
                is ExportJobState.Running -> json.put("state", "running")
                is ExportJobState.Done -> json.put("state", "done").put("files", JSONArray(state.files.map { it.path }))
                is ExportJobState.Failed -> json.put("state", "failed").put("message", state.message)
                ExportJobState.Cancelled -> json.put("state", "cancelled")
            }
            array.put(json)
        }
        check(preferences.edit().putString("jobs", array.toString()).commit()) { "无法保存导出任务" }
        mutableJobs.value = entries.values.map { it.job }.sortedByDescending { it.startedAt }
    }

    companion object {
        private const val WORK_NAME_PREFIX = "audio-export-"
    }
}
