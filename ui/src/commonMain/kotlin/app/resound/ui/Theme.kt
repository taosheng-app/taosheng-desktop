package app.resound.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 涛声 / 回响配色：浅灰白暖底 + 纯白卡片 + 暖橙点缀；深色模式为暖调深灰。 */
@Immutable
data class RsColors(
    val background: Color,
    val card: Color,
    val cardPressed: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
    val accent: Color,
    val accentSoft: Color,
    val good: Color,
    val warning: Color,
    val critical: Color,
    val shadow: Color,
    val isDark: Boolean,
)

val LightRs = RsColors(
    background = Color(0xFFF4F2EF),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFF7F4F0),
    text = Color(0xFF1D1B19),
    textSecondary = Color(0xFF7D7770),
    textTertiary = Color(0xFFADA79F),
    separator = Color(0xFFECE8E3),
    accent = Color(0xFFF07B2E),
    accentSoft = Color(0xFFFDEBDD),
    good = Color(0xFF34A853),
    warning = Color(0xFFE6A23C),
    critical = Color(0xFFE5484D),
    shadow = Color(0x14583A1C),
    isDark = false,
)

val DarkRs = RsColors(
    background = Color(0xFF171513),
    card = Color(0xFF24211E),
    cardPressed = Color(0xFF2C2825),
    text = Color(0xFFF3EFEA),
    textSecondary = Color(0xFFA39C94),
    textTertiary = Color(0xFF6E6862),
    separator = Color(0xFF33302C),
    accent = Color(0xFFF59A5B),
    accentSoft = Color(0xFF3A2A1E),
    good = Color(0xFF5BC27A),
    warning = Color(0xFFEDB75E),
    critical = Color(0xFFF07175),
    shadow = Color(0x00000000),
    isDark = true,
)

/** 与 Android 端一致的字号层级。 */
object RsType {
    val largeTitle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val subhead = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val number = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
}

enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

val LocalRs = staticCompositionLocalOf { LightRs }

object Rs {
    val colors: RsColors
        @Composable get() = LocalRs.current
}

@Composable
fun TaoShengTheme(
    mode: ThemeMode = ThemeMode.Light,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val rs = if (dark) DarkRs else LightRs
    val material = if (dark) {
        darkColorScheme(
            primary = rs.accent,
            onPrimary = Color.White,
            primaryContainer = rs.accentSoft,
            onPrimaryContainer = rs.accent,
            secondary = rs.accent,
            background = rs.background,
            onBackground = rs.text,
            surface = rs.card,
            onSurface = rs.text,
            surfaceVariant = rs.cardPressed,
            onSurfaceVariant = rs.textSecondary,
            outline = rs.separator,
            error = rs.critical,
        )
    } else {
        lightColorScheme(
            primary = rs.accent,
            onPrimary = Color.White,
            primaryContainer = rs.accentSoft,
            onPrimaryContainer = rs.accent,
            secondary = rs.accent,
            background = rs.background,
            onBackground = rs.text,
            surface = rs.card,
            onSurface = rs.text,
            surfaceVariant = rs.cardPressed,
            onSurfaceVariant = rs.textSecondary,
            outline = rs.separator,
            error = rs.critical,
        )
    }
    val typography = androidx.compose.material3.Typography(
        headlineMedium = RsType.largeTitle.copy(color = rs.text),
        titleLarge = RsType.title.copy(color = rs.text),
        titleMedium = RsType.headline.copy(color = rs.text),
        titleSmall = RsType.subhead.copy(fontWeight = FontWeight.Medium, color = rs.text),
        bodyMedium = RsType.body.copy(color = rs.text),
        bodySmall = RsType.caption.copy(color = rs.textSecondary),
        labelSmall = RsType.caption.copy(color = rs.textTertiary),
    )
    CompositionLocalProvider(LocalRs provides rs) {
        MaterialTheme(
            colorScheme = material,
            typography = typography,
            content = content,
        )
    }
}
