package app.resound.digest

import app.resound.data.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/** 与整理服务/密钥分开持有，仍使用原来的 digest 设置文件。 */
class AndroidDigestTiming(private val prefs: KeyValueStore) : DigestTiming {
    private val mutableMode = MutableStateFlow(runCatching {
        DigestMode.valueOf(prefs.getString("digestMode", "Interval")!!)
    }.getOrDefault(DigestMode.Interval))
    override val mode = mutableMode.asStateFlow()
    private val mutablePoints = MutableStateFlow(normalize(prefs.getString("timePoints", "")!!
        .split(',').mapNotNull(String::toIntOrNull)))
    override val timePoints = mutablePoints.asStateFlow()
    private val mutableNext = MutableStateFlow<Long?>(null)
    override val nextDigestAt = mutableNext.asStateFlow()
    var onChanged: () -> Unit = {}

    @Synchronized override fun setMode(mode: DigestMode) {
        if (mode == mutableMode.value) return
        check(prefs.edit().putString("digestMode", mode.name).commit())
        mutableMode.value = mode
        onChanged()
    }
    @Synchronized override fun addTimePoint(minutes: Int) {
        if (minutes !in 0..1439 || minutes in timePoints.value || timePoints.value.size >= DigestTiming.MAX_TIME_POINTS) return
        save(timePoints.value + minutes)
    }
    @Synchronized override fun removeTimePoint(minutes: Int) {
        if (minutes !in timePoints.value) return
        save(timePoints.value - minutes)
    }
    private fun save(points: List<Int>) {
        val normalized = normalize(points)
        check(prefs.edit().putString("timePoints", normalized.joinToString(",")).commit())
        mutablePoints.value = normalized
        onChanged()
    }
    internal fun next(at: Long?) { mutableNext.value = at }
    /** Desktop scheduling is delegated to the application scheduler, which may not guarantee exact wall-clock delivery. */
    override fun canScheduleExact() = false

    companion object {
        private fun normalize(points: List<Int>) = points.filter { it in 0..1439 }.distinct().sorted().take(DigestTiming.MAX_TIME_POINTS)
    }
}

internal object DigestTime {
    /** 严格晚于 now；按当地日历构造，跨午夜和时区变化都重新计算。 */
    fun next(points: List<Int>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..2L).flatMap { offset ->
            points.filter { it in 0..1439 }.map { minute ->
                date.plusDays(offset).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
            }
        }.filter { it > now }.minOrNull()
    }
    fun missed(now: Long, target: Long, expires: Long) = now < target || expires <= target || now >= expires
}
