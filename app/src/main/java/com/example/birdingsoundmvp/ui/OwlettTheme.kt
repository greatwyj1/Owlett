package com.example.birdingsoundmvp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.material3.ColorScheme
import com.example.birdingsoundmvp.settings.ColorTheme

private val LightPalette = lightColorScheme(
    primary = Color(0xFF187565), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEE8), onPrimaryContainer = Color(0xFF123D33),
    secondary = Color(0xFF356B7A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EFF2), onSecondaryContainer = Color(0xFF21434C),
    tertiary = Color(0xFF976014), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEACC), onTertiaryContainer = Color(0xFF613B08),
    background = Color(0xFFF7F9F8), onBackground = Color(0xFF202C2A),
    surface = Color.White, onSurface = Color(0xFF202C2A),
    surfaceVariant = Color(0xFFEAF0ED), onSurfaceVariant = Color(0xFF5B6A65),
    surfaceContainer = Color(0xFFF0F4F2), surfaceContainerHigh = Color(0xFFE8EFEB),
    surfaceContainerLow = Color(0xFFF8FAF9), surfaceContainerHighest = Color(0xFFE2EAE6),
    outline = Color(0xFF7B8B84), outlineVariant = Color(0xFFDCE4DF),
    error = Color(0xFFB73D40), errorContainer = Color(0xFFFFE5E5)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF85D5BE), onPrimary = Color(0xFF0B392E),
    primaryContainer = Color(0xFF1F4E42), onPrimaryContainer = Color(0xFFB7F0DF),
    secondary = Color(0xFF9FCFDB), onSecondary = Color(0xFF183C47),
    secondaryContainer = Color(0xFF254A55), onSecondaryContainer = Color(0xFFC5E8F0),
    tertiary = Color(0xFFECC184), onTertiary = Color(0xFF4B3010),
    tertiaryContainer = Color(0xFF584222), onTertiaryContainer = Color(0xFFFFDEB3),
    background = Color(0xFF131918), onBackground = Color(0xFFE1EAE6),
    surface = Color(0xFF19211E), onSurface = Color(0xFFE1EAE6),
    surfaceVariant = Color(0xFF303C36), onSurfaceVariant = Color(0xFFB0BFB6),
    surfaceContainer = Color(0xFF202B25), surfaceContainerHigh = Color(0xFF29352F),
    surfaceContainerLow = Color(0xFF18211C), surfaceContainerHighest = Color(0xFF34423A),
    outline = Color(0xFF87998E), outlineVariant = Color(0xFF3C4B42),
    error = Color(0xFFFFB3B3), errorContainer = Color(0xFF57292C)
)

internal fun owlettColors(theme: String, dark: Boolean): ColorScheme {
    val base = if (dark) DarkPalette else LightPalette
    return when (ColorTheme.normalize(theme)) {
        "feather" -> if (dark) base.copy(
            primary = Color(0xFFD5C4AD), onPrimary = Color(0xFF352C20),
            primaryContainer = Color(0xFF514535), onPrimaryContainer = Color(0xFFF2E0C7),
            background = Color(0xFF191919), onBackground = Color(0xFFE9E6E1),
            surface = Color(0xFF202020), onSurface = Color(0xFFE9E6E1),
            surfaceVariant = Color(0xFF393733), onSurfaceVariant = Color(0xFFC7C2B9),
            surfaceContainerLowest = Color(0xFF141414), surfaceContainerLow = Color(0xFF242321),
            surfaceContainer = Color(0xFF282725), surfaceContainerHigh = Color(0xFF302E2A),
            surfaceContainerHighest = Color(0xFF393630), outline = Color(0xFFA39A8E), outlineVariant = Color(0xFF4A453E)
        ) else base.copy(
            primary = Color(0xFF71604A), onPrimary = Color.White,
            primaryContainer = Color(0xFFECE3D6), onPrimaryContainer = Color(0xFF3C3021),
            background = Color(0xFFF7F8F8), onBackground = Color(0xFF2D2C29),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF2D2C29),
            surfaceVariant = Color(0xFFF0EEEA), onSurfaceVariant = Color(0xFF67635B),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAF9),
            surfaceContainer = Color(0xFFF3F2EF), surfaceContainerHigh = Color(0xFFEDEAE5),
            surfaceContainerHighest = Color(0xFFE5E0D9), outline = Color(0xFF8F877A), outlineVariant = Color(0xFFE2DDD5)
        )
        "gold" -> if (dark) base.copy(
            primary = Color(0xFFE6BE79), onPrimary = Color(0xFF3F2D0C),
            primaryContainer = Color(0xFF57411D), onPrimaryContainer = Color(0xFFFFDEAB),
            background = Color(0xFF1B1916), onBackground = Color(0xFFECE6DC),
            surface = Color(0xFF221F1A), onSurface = Color(0xFFECE6DC),
            surfaceVariant = Color(0xFF3C372E), onSurfaceVariant = Color(0xFFCDC3B3),
            surfaceContainerLowest = Color(0xFF151310), surfaceContainerLow = Color(0xFF26221C),
            surfaceContainer = Color(0xFF2B261F), surfaceContainerHigh = Color(0xFF332E25),
            surfaceContainerHighest = Color(0xFF3F372C), outline = Color(0xFFAA9A83), outlineVariant = Color(0xFF4C4233)
        ) else base.copy(
            primary = Color(0xFF88601F), onPrimary = Color.White,
            primaryContainer = Color(0xFFF5E5C8), onPrimaryContainer = Color(0xFF49310E),
            background = Color(0xFFFCFAF6), onBackground = Color(0xFF312D27),
            surface = Color(0xFFFFFEFB), onSurface = Color(0xFF312D27),
            surfaceVariant = Color(0xFFF2ECE1), onSurfaceVariant = Color(0xFF6C6252),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF9F3),
            surfaceContainer = Color(0xFFF7F1E7), surfaceContainerHigh = Color(0xFFF0E7D9),
            surfaceContainerHighest = Color(0xFFE9DDCA), outline = Color(0xFF998974), outlineVariant = Color(0xFFE5DAC9)
        )
        else -> base
    }
}

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp
)
private val OwlettTypography = Typography(
    displayLarge = type(36, 44), displayMedium = type(32, 40), displaySmall = type(28, 36),
    headlineLarge = type(26, 34, FontWeight.SemiBold), headlineMedium = type(24, 32, FontWeight.SemiBold), headlineSmall = type(22, 30, FontWeight.SemiBold),
    titleLarge = type(22, 30, FontWeight.SemiBold), titleMedium = type(17, 24, FontWeight.SemiBold), titleSmall = type(15, 22, FontWeight.SemiBold),
    bodyLarge = type(16, 25), bodyMedium = type(14, 22), bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(11, 16)
)

@Composable
fun OwlettTheme(appearance: String, colorTheme: String = "green", content: @Composable () -> Unit) {
    val dark = when (appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = owlettColors(colorTheme, dark), typography = OwlettTypography,
        shapes = Shapes(RoundedCornerShape(4.dp), RoundedCornerShape(6.dp), RoundedCornerShape(8.dp), RoundedCornerShape(8.dp), RoundedCornerShape(8.dp)),
        content = content)
}
