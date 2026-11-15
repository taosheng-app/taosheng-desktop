package app.resound.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.ui.Rs
import app.resound.ui.RsType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class DesktopTab(val title: String) {
    Today("今天"),
    Ask("问问"),
    Memory("记忆"),
    Todo("待办"),
    Settings("设置"),
}

internal val HeroOrangeStart = Color(0xFFF68B3C)
internal val HeroOrangeEnd = Color(0xFFE5611A)

private val CLOCK_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val FULL_DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun formatClock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CLOCK_FMT)

internal fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME_FMT)

internal fun formatFullDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(FULL_DATE_TIME_FMT)

internal fun partOfDay(millis: Long): String {
    val hour = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour
    return when {
        hour < 6 -> "凌晨"
        hour < 12 -> "上午"
        hour < 14 -> "中午"
        hour < 18 -> "下午"
        else -> "晚上"
    }
}

internal fun formatDurationShort(totalMillis: Long): String {
    val seconds = (totalMillis / 1000L).coerceAtLeast(0L)
    if (seconds < 60L) return "${seconds}秒"
    val minutes = seconds / 60L
    return "${minutes}分钟"
}

/** 白色圆角卡片，带暖调极浅阴影，对齐 Android 端 RsCard。 */
@Composable
internal fun RsCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    radius: Dp = 20.dp,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Rs.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 8.dp, RoundedCornerShape(radius), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(radius))
            .background(color ?: c.card)
            .padding(padding),
        content = content,
    )
}

/** 暖橙渐变主状态卡，对齐 Android 端今天页 Hero Card。 */
@Composable
internal fun RsHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Rs.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (c.isDark) 0.dp else 14.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x33E5611A), spotColor = Color(0x33E5611A))
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(HeroOrangeStart, HeroOrangeEnd)))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 胶囊按钮：实心暖橙或浅橙柔底，支持禁用态。 */
@Composable
internal fun RsPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    backgroundColor: Color? = null,
    contentColor: Color? = null,
) {
    val c = Rs.colors
    val bg = when {
        backgroundColor != null -> if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.45f)
        filled -> if (enabled) c.accent else c.accent.copy(alpha = 0.35f)
        else -> if (enabled) c.accentSoft else c.separator.copy(alpha = 0.55f)
    }
    val fg = when {
        contentColor != null -> if (enabled) contentColor else contentColor.copy(alpha = 0.6f)
        filled -> Color.White
        else -> if (enabled) c.accent else c.textTertiary
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 筛选胶囊芯片：选中为暖橙实心白字，未选中为卡片白底+浅框。 */
@Composable
internal fun RsFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Rs.colors
    Surface(
        modifier = modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = if (selected) c.accent else c.card,
        border = if (selected) null else BorderStroke(1.dp, c.separator),
    ) {
        Text(
            text = label,
            style = RsType.caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) Color.White else c.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
internal fun RsBadge(
    text: String,
    bg: Color = Rs.colors.accentSoft,
    fg: Color = Rs.colors.accent,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
    }
}

@Composable
internal fun RsSectionHeader(
    title: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Rs.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = RsType.section.copy(fontWeight = FontWeight.SemiBold), color = c.textSecondary)
        if (trailing != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

@Composable
internal fun RsDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Rs.colors.separator).padding(top = 1.dp))
}

@Composable
internal fun RsDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
