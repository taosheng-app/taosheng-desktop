package app.resound.transcribe

import app.resound.data.KeyValueStore

/**
 * 实时转写：录完一段就转。
 * 桌面版没有“锁屏攒批”和电量/温度信号（任务卡 005）：电脑视为一直接电、不过热，因此总是立即转写；
 * 判断函数保留原样，便于以后接入笔记本电池状态。
 */
object EarlyTranscribe {
    const val LOW_BATTERY = 20

    fun shouldBatch(interactive: Boolean, charging: Boolean): Boolean = !interactive && !charging

    fun shouldRun(realtime: Boolean, pendingMillis: Long, battery: Int, charging: Boolean, hot: Boolean): Boolean = when {
        !realtime || pendingMillis <= 0 -> false
        hot -> false
        !charging && battery in 0 until LOW_BATTERY -> false
        else -> true
    }

    /** 桌面：电量未知（-1）、视为接电、不过热。 */
    fun deviceAllows(realtime: Boolean, pendingMillis: Long): Boolean =
        shouldRun(realtime, pendingMillis, battery = -1, charging = true, hot = false)
}

/**
 * 每轮流水线各步用时，最近 30 轮，写进诊断记录，看慢在哪一步。
 * 由装配层在启动时设置 [store]（原安卓版用名为 pipeline-times 的 SharedPreferences）；未设置时记录被丢弃。
 */
object PipelineTimes {
    private const val KEY = "runs"
    private const val SEGMENTS = "segments073"
    @Volatile var store: KeyValueStore? = null
    /** 省电诊断目录；未设置时不记录。 */
    @Volatile var powerDirectory: java.io.File? = null

    @Synchronized fun record(line: String) = append(KEY, line, 30)
    @Synchronized fun recordSegment(line: String) = append(SEGMENTS, line, 300)
    private fun append(key: String, line: String, keep: Int) {
        val prefs = store ?: return
        val rows = (prefs.getString(key, "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(keep)
        prefs.edit().putString(key, rows.joinToString("\n")).apply()
    }

    fun dump(): String {
        val prefs = store ?: return ""
        return prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }.joinToString("\n") { "流水线 $it" } + "\n" +
            prefs.getString(SEGMENTS, "").orEmpty() + "\n" + (powerDirectory?.let { TranscribePowerDiagnostics.dump(it) } ?: "")
    }
}
