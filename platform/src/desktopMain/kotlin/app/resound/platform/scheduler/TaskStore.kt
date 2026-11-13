package app.resound.platform.scheduler

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条任务的持久化记录；[pending] 是 AppendOrReplace 排在后面的任务。 */
internal data class Record(
    val name: String,
    val worker: String,
    val input: TaskInput,
    val network: NetworkRequirement,
    val backoffPolicy: BackoffPolicy,
    val backoffMillis: Long,
    val tags: Set<String>,
    val periodMillis: Long?,
    val expedited: Boolean,
    val state: TaskState,
    val attempts: Int,
    val nextRunAt: Long,
    val pending: Record? = null,
) {
    fun backoff() = Backoff(backoffPolicy, backoffMillis)
    fun info() = TaskInfo(name, worker, state, attempts, nextRunAt, tags, periodMillis != null)

    companion object {
        fun from(name: String, r: TaskRequest, now: Long) = Record(
            name, r.worker, r.input, r.network, r.backoff.policy, r.backoff.delayMillis, r.tags,
            r.periodMillis, r.expedited, TaskState.Enqueued, 0, now + r.initialDelayMillis,
        )
    }
}

/** 任务表落盘：每次变更原子重写整个文件（任务数量很少，几十条量级）。 */
internal class TaskStore(private val file: File) {
    private val records = LinkedHashMap<String, Record>()

    init { load() }

    fun get(name: String) = records[name]
    fun all(): List<Record> = records.values.toList()

    fun put(rec: Record) {
        records[rec.name] = rec
        save()
    }

    /** 重启后：被打断的 Running 重新排队；周期任务错过多个周期只保留一次（nextRunAt 已过期即“到期补跑一次”）。 */
    fun recoverAfterRestart(now: Long) {
        var touched = false
        for (rec in all()) {
            if (rec.state == TaskState.Running) {
                records[rec.name] = rec.copy(state = TaskState.Enqueued, nextRunAt = now); touched = true
            }
        }
        if (touched) save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(records.values.map(::toJson)).toString(), Charsets.UTF_8)
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i)).let { records[it.name] = it }
        } catch (e: Exception) {
            runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
            records.clear()
        }
    }

    private fun toJson(r: Record): JSONObject = JSONObject()
        .put("name", r.name).put("worker", r.worker).put("network", r.network.name)
        .put("backoffPolicy", r.backoffPolicy.name).put("backoffMillis", r.backoffMillis)
        .put("tags", JSONArray(r.tags.toList())).put("periodMillis", r.periodMillis ?: JSONObject.NULL)
        .put("expedited", r.expedited).put("state", r.state.name).put("attempts", r.attempts).put("nextRunAt", r.nextRunAt)
        .put("input", JSONObject().also { o ->
            r.input.forEach { (k, v) ->
                o.put(k, JSONObject().put("t", v?.let { it::class.simpleName } ?: "null").put("v", v ?: JSONObject.NULL))
            }
        })
        .put("pending", r.pending?.let(::toJson) ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject): Record {
        val inputObj = o.getJSONObject("input")
        val input = HashMap<String, Any?>()
        for (key in inputObj.keys()) {
            val cell = inputObj.getJSONObject(key)
            input[key] = when (cell.getString("t")) {
                "String" -> cell.getString("v")
                "Int" -> cell.getInt("v")
                "Long" -> cell.getLong("v")
                "Boolean" -> cell.getBoolean("v")
                else -> null
            }
        }
        return Record(
            name = o.getString("name"), worker = o.getString("worker"), input = input,
            network = NetworkRequirement.valueOf(o.getString("network")),
            backoffPolicy = BackoffPolicy.valueOf(o.getString("backoffPolicy")), backoffMillis = o.getLong("backoffMillis"),
            tags = o.getJSONArray("tags").let { a -> (0 until a.length()).map(a::getString).toSet() },
            periodMillis = if (o.isNull("periodMillis")) null else o.getLong("periodMillis"),
            expedited = o.getBoolean("expedited"), state = TaskState.valueOf(o.getString("state")),
            attempts = o.getInt("attempts"), nextRunAt = o.getLong("nextRunAt"),
            pending = if (o.isNull("pending")) null else fromJson(o.getJSONObject("pending")),
        )
    }
}