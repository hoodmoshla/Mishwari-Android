package ye.mishwari.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val MishwariBrightGreen = Color(0xFF07823F)
internal val MishwariOrange = Color(0xFFF0A329)
internal val MishwariBlue = Color(0xFF2F6CB4)
internal val MishwariLavender = Color(0xFF7257A8)

private val MishwariLightColors = lightColorScheme(
    primary = Color(0xFF086B46),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7F2E0),
    onPrimaryContainer = Color(0xFF082317),
    inversePrimary = Color(0xFFA0DDB8),
    secondary = Color(0xFF9A5200),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE6C8),
    onSecondaryContainer = Color(0xFF321A00),
    tertiary = Color(0xFF62519A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECE6FF),
    onTertiaryContainer = Color(0xFF211744),
    background = Color(0xFFF5F7F3),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFE5ECE6),
    onSurfaceVariant = Color(0xFF414A43),
    inverseSurface = Color(0xFF2C342E),
    inverseOnSurface = Color(0xFFF0F3EF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF727C74),
    outlineVariant = Color(0xFFC5CEC7),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF086B46),
    surfaceDim = Color(0xFFD9E0D9),
    surfaceBright = Color(0xFFFFFEFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6F1),
    surfaceContainer = Color(0xFFEDF1EC),
    surfaceContainerHigh = Color(0xFFE7ECE7),
    surfaceContainerHighest = Color(0xFFE1E7E1),
)

private val MishwariDarkColors = darkColorScheme(
    primary = Color(0xFFA0DDB8),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFD7F2E0),
    inversePrimary = Color(0xFF086B46),
    secondary = Color(0xFFFFBD76),
    onSecondary = Color(0xFF512900),
    secondaryContainer = Color(0xFF733B00),
    onSecondaryContainer = Color(0xFFFFDDB7),
    tertiary = Color(0xFFD0C4FF),
    onTertiary = Color(0xFF35275F),
    tertiaryContainer = Color(0xFF4C3D76),
    onTertiaryContainer = Color(0xFFECE6FF),
    background = Color(0xFF101712),
    onBackground = Color(0xFFE1E7E1),
    surface = Color(0xFF151C17),
    onSurface = Color(0xFFE1E7E1),
    surfaceVariant = Color(0xFF3E4941),
    onSurfaceVariant = Color(0xFFC0CBC2),
    inverseSurface = Color(0xFFE1E7E1),
    inverseOnSurface = Color(0xFF2C342E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A968D),
    outlineVariant = Color(0xFF3E4941),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFA0DDB8),
    surfaceDim = Color(0xFF101712),
    surfaceBright = Color(0xFF353D37),
    surfaceContainerLowest = Color(0xFF0B120D),
    surfaceContainerLow = Color(0xFF19211B),
    surfaceContainer = Color(0xFF1D251F),
    surfaceContainerHigh = Color(0xFF273029),
    surfaceContainerHighest = Color(0xFF323B34),
)

private val MishwariTypography = Typography(
    displayLarge = arabicStyle(57, 64, FontWeight.Normal),
    displayMedium = arabicStyle(45, 54, FontWeight.Normal),
    displaySmall = arabicStyle(36, 44, FontWeight.Normal),
    headlineLarge = arabicStyle(32, 40, FontWeight.Normal),
    headlineMedium = arabicStyle(28, 36, FontWeight.Normal),
    headlineSmall = arabicStyle(24, 32, FontWeight.SemiBold),
    titleLarge = arabicStyle(22, 30, FontWeight.SemiBold),
    titleMedium = arabicStyle(16, 26, FontWeight.Medium),
    titleSmall = arabicStyle(14, 22, FontWeight.Medium),
    bodyLarge = arabicStyle(16, 28, FontWeight.Normal),
    bodyMedium = arabicStyle(14, 24, FontWeight.Normal),
    bodySmall = arabicStyle(12, 20, FontWeight.Normal),
    labelLarge = arabicStyle(14, 22, FontWeight.Medium),
    labelMedium = arabicStyle(12, 20, FontWeight.Medium),
    labelSmall = arabicStyle(11, 18, FontWeight.Medium),
)

private val MishwariShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
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

/** Applies Mishwari's green identity and follows either the system or an explicit user choice. */
@Composable
internal fun MishwariTheme(themeMode: String? = null, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colors = if (darkTheme) MishwariDarkColors else MishwariLightColors

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colors,
            typography = MishwariTypography,
            shapes = MishwariShapes,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag(if (darkTheme) "themeDark" else "themeLight"),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}
