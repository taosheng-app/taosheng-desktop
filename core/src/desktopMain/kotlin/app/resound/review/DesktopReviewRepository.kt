package app.resound.review

import app.resound.data.ReviewDao
import app.resound.data.ReviewEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

/** Desktop adapter for saved reviews. It reads existing rows and does not invoke Android workers or LLMs. */
class DesktopReviewRepository(
    private val dao: ReviewDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ReviewRepository {
    private val mutableState = MutableStateFlow(ReviewState(
        generating = false,
        lastError = null,
        dailyAtMinutes = DEFAULT_REVIEW_MINUTES,
        pushEnabled = false,
    ))
    override val state: StateFlow<ReviewState> = mutableState

    override fun daily(date: LocalDate): Flow<DailyReview?> =
        dao.daily(date.toString()).map { it?.toDailyReview() }

    override fun recent(): Flow<List<DailyReview>> =
        dao.recent().map { rows -> rows.map { it.toDailyReview() } }

    override fun generateNow(date: LocalDate) {
        mutableState.value = mutableState.value.copy(
            generating = false,
            lastError = "桌面版暂不支持生成每日回顾。",
            lastErrorDate = date,
        )
    }

    override fun hasMaterial(date: LocalDate): Flow<Boolean> = dao.materialChanges()
        .map {
            val timezone = zone()
            val start = date.minusDays(1).atTime(DEFAULT_REVIEW_MINUTES / 60, DEFAULT_REVIEW_MINUTES % 60)
                .atZone(timezone).toInstant().toEpochMilli()
            val end = date.atTime(DEFAULT_REVIEW_MINUTES / 60, DEFAULT_REVIEW_MINUTES % 60)
                .atZone(timezone).toInstant().toEpochMilli()
            val input = dao.input(start, end)
            input.events.isNotEmpty() || input.items.isNotEmpty()
        }
        .distinctUntilChanged()

    override fun windowText(date: LocalDate): String {
        val start = date.minusDays(1)
        return "${start.monthValue}月${start.dayOfMonth}日 22:00 – ${date.monthValue}月${date.dayOfMonth}日 22:00"
    }

    override fun setDailyAt(minutes: Int) {
        throw UnsupportedOperationException("桌面版暂不支持设置每日回顾时间。")
    }

    override fun setPushEnabled(enabled: Boolean) {
        throw UnsupportedOperationException("桌面版暂不支持每日回顾推送。")
    }

    private fun ReviewEntity.toDailyReview(): DailyReview = DailyReview(
        date = LocalDate.parse(startDate),
        brief = brief,
        sections = decodeSections(sectionsJson),
        generatedAt = generatedAt,
    )

    private fun decodeSections(json: String): List<ReviewSection> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val linesJson = row.optJSONArray("lines") ?: return@mapNotNull null
            val idsJson = row.optJSONArray("eventIds")
            val lines = (0 until linesJson.length()).map { linesJson.optString(it).orEmpty() }
            val ids = lines.indices.map { lineIndex ->
                idsJson?.takeIf { lineIndex < it.length() && !it.isNull(lineIndex) }?.optLong(lineIndex)
            }
            ReviewSection(row.optString("title"), lines, ids)
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val DEFAULT_REVIEW_MINUTES = 22 * 60
    }
}
