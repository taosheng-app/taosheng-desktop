package app.resound.push

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/*
 * `PushRecords.kt`（数据层）依赖的 push 包内小工具。
 * 安卓版分别在 `Subscriptions.kt`（strings/objects）与 `PushDiagnostics.kt`（PushTrace/localRecord）里，
 * 它们随 push 业务整体迁移（波次 5）；这里只提前放入数据层需要的最小集合，签名与安卓一致，
 * 波次 5 迁移 push 时把本文件合并进原位置，不要重复定义。
 */

internal fun JSONArray.strings() = (0 until length()).map { get(it).toString() }
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

/** 每次推送独立，随协程切线程传播；仅收集类别和数量，不保存内容。 */
internal class PushTrace {
    var stage = "打包本地数据"
    var exception = ""
    val skipped = linkedSetOf<String>()
    companion object {
        val local = ThreadLocal<PushTrace?>()
        fun current() = local.get()
    }
}

/** 单条本地记录格式异常时跳过它而不是让整次推送失败；取消和程序错误照常抛出。 */
internal inline fun <T> localRecord(kind: String, id: Long, block: () -> T): T? = try { block() }
catch (e: kotlinx.coroutines.CancellationException) { throw e }
catch (e: Exception) {
    if (e !is org.json.JSONException && e !is IllegalArgumentException && e !is ClassCastException && e !is java.time.DateTimeException) throw e
    PushTrace.current()?.apply { skipped += "$kind:$id"; exception = e.javaClass.simpleName }
    null
}