package ye.mishwari.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection

private val MishwariLightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F0DE),
    onPrimaryContainer = Color(0xFF002117),
    inversePrimary = Color(0xFF8DD5BF),
    secondary = Color(0xFF52665F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8DF),
    onSecondaryContainer = Color(0xFF0F1F19),
    tertiary = Color(0xFF625B78),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DDF8),
    onTertiaryContainer = Color(0xFF1E1732),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF191D1A),
    surface = Color(0xFFFCFDF9),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFE5E9E4),
    onSurfaceVariant = Color(0xFF414843),
    inverseSurface = Color(0xFF2E332F),
    inverseOnSurface = Color(0xFFF0F2ED),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF717A73),
    outlineVariant = Color(0xFFC4CCC5),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF176B5B),
    surfaceDim = Color(0xFFD9DDD8),
    surfaceBright = Color(0xFFFCFDF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F1),
    surfaceContainer = Color(0xFFEDEFEA),
    surfaceContainerHigh = Color(0xFFE7EAE5),
    surfaceContainerHighest = Color(0xFFE1E5DF),
)

private val MishwariDarkColors = darkColorScheme(
    primary = Color(0xFF8DD5BF),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFB8F0DE),
    inversePrimary = Color(0xFF176B5B),
    secondary = Color(0xFFB9CCC3),
    onSecondary = Color(0xFF24352E),
    secondaryContainer = Color(0xFF3A4B44),
    onSecondaryContainer = Color(0xFFD5E8DF),
    tertiary = Color(0xFFCEC1E4),
    onTertiary = Color(0xFF342D49),
    tertiaryContainer = Color(0xFF4A4260),
    onTertiaryContainer = Color(0xFFE8DDF8),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE1E4DE),
    surface = Color(0xFF151A17),
    onSurface = Color(0xFFE1E4DE),
    surfaceVariant = Color(0xFF414843),
    onSurfaceVariant = Color(0xFFC1C9C1),
    inverseSurface = Color(0xFFE1E4DE),
    inverseOnSurface = Color(0xFF2E332F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8B938C),
    outlineVariant = Color(0xFF414843),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF8DD5BF),
    surfaceDim = Color(0xFF101411),
    surfaceBright = Color(0xFF363B37),
    surfaceContainerLowest = Color(0xFF0C100D),
    surfaceContainerLow = Color(0xFF191E1B),
    surfaceContainer = Color(0xFF1D221F),
    surfaceContainerHigh = Color(0xFF272C29),
    surfaceContainerHighest = Color(0xFF323733),
)

private val MishwariTypography = Typography(
    displayLarge = arabicStyle(57, 64, FontWeight.Normal),
    displayMedium = arabicStyle(45, 54, FontWeight.Normal),
    displaySmall = arabicStyle(36, 44, FontWeight.Normal),
    headlineLarge = arabicStyle(32, 40, FontWeight.Normal),
    headlineMedium = arabicStyle(28, 36, FontWeight.Normal),
    headlineSmall = arabicStyle(24, 32, FontWeight.Medium),
    titleLarge = arabicStyle(22, 30, FontWeight.Medium),
    titleMedium = arabicStyle(16, 26, FontWeight.Medium),
    titleSmall = arabicStyle(14, 22, FontWeight.Medium),
    bodyLarge = arabicStyle(16, 28, FontWeight.Normal),
    bodyMedium = arabicStyle(14, 24, FontWeight.Normal),
    bodySmall = arabicStyle(12, 20, FontWeight.Normal),
    labelLarge = arabicStyle(14, 22, FontWeight.Medium),
    labelMedium = arabicStyle(12, 20, FontWeight.Medium),
    labelSmall = arabicStyle(11, 18, FontWeight.Medium),
)

private fun arabicStyle(sizeSp: Int, lineHeightSp: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** Applies the system-selected Mishwari palette; callers cannot override the color mode. */
@Composable
internal fun MishwariTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) MishwariDarkColors else MishwariLightColors

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colors,
            typography = MishwariTypography,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag(if (darkTheme) "themeDark" else "themeLight"),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}
