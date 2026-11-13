package app.resound.review

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

data class ReviewSection(val title: String, val lines: List<String>, val eventIds: List<Long?>)

data class DailyReview(
    val date: LocalDate,
    val brief: String,
    val sections: List<ReviewSection>,
    val generatedAt: Long,
)

data class ReviewState(
    val generating: Boolean,
    val lastError: String?,
    val dailyAtMinutes: Int,
    val pushEnabled: Boolean,
    val stage: String? = null,
    val lastErrorDate: LocalDate? = null,
)

interface ReviewRepository {
    fun daily(date: LocalDate): Flow<DailyReview?>
    fun recent(): Flow<List<DailyReview>>
    val state: StateFlow<ReviewState>
    fun generateNow(date: LocalDate)
    fun hasMaterial(date: LocalDate): Flow<Boolean>
    fun windowText(date: LocalDate): String
    fun setDailyAt(minutes: Int)
    fun setPushEnabled(enabled: Boolean)
}
