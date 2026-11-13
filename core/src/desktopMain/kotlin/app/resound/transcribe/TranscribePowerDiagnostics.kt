package app.resound.transcribe

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

/** 每日执行次数与（桌面恒为 0 的）本地推理持锁时间。文件锁 + 临时文件原子替换，避免丢增量。 */
internal object TranscribePowerDiagnostics {
    private const val DAYS = 30

    fun execution(directory: File, batched: Boolean, at: Long = System.currentTimeMillis()) = update(directory) { days ->
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        add(days, day, if (batched) "batched" else "expedited", 1)
    }

    /** 用单调时钟算出的实际持有时间；跨午夜按本地日期拆分。 */
    fun held(directory: File, startedAt: Long, millis: Long) {
        if (millis <= 0) return
        update(directory) { days ->
            var at = startedAt
            var remaining = millis
            val zone = ZoneId.systemDefault()
            while (remaining > 0) {
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val part = minOf(remaining, midnight - at)
                add(days, day.toString(), "heldMillis", part)
                remaining -= part
                at += part
            }
        }
    }

    private fun add(days: JSONObject, day: String, key: String, value: Long) {
        val row = days.optJSONObject(day) ?: JSONObject().also { days.put(day, it) }
        row.put(key, row.optLong(key) + value)
    }

    private fun update(directory: File, change: (JSONObject) -> Unit) {
        runCatching {
            access(directory) { file ->
                val days = read(file)
                change(days)
                days.keys().asSequence().toList().sortedDescending().drop(DAYS).forEach(days::remove)
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(days.toString().toByteArray(Charsets.UTF_8))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }.onFailure { System.err.println("转写省电诊断写入失败：${it.message}") }
    }

    fun dump(directory: File): String = runCatching {
        access(directory) { file ->
            val days = read(file)
            days.keys().asSequence().toList().sorted().joinToString("\n") { day ->
                val row = days.getJSONObject(day)
                val expedited = row.optLong("expedited")
                val batched = row.optLong("batched")
                "转写省电 day=$day localAsrHeldMillis=${row.optLong("heldMillis")} " +
                    "realtimeExecutions=${expedited + batched} expedited=$expedited batched=$batched"
            }
        }
    }.getOrElse { "转写省电 诊断读取失败" }

    private fun read(file: File): JSONObject =
        if (!file.exists()) JSONObject() else JSONObject(file.readText(Charsets.UTF_8))

    @Synchronized private fun <T> access(directory: File, block: (File) -> T): T {
        directory.mkdirs()
        return RandomAccessFile(File(directory, "daily.lock"), "rw").use { handle ->
            handle.channel.lock().use { block(File(directory, "daily.json")) }
        }
    }
}
