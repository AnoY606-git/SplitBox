// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.splitbox.app.model.AppSettings
import dev.splitbox.app.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CE7CE),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF204C43),
    onPrimaryContainer = Color(0xFFC0F8E7),
    secondary = Color(0xFFB6C8C1),
    onSecondary = Color(0xFF22352E),
    secondaryContainer = Color(0xFF30423C),
    onSecondaryContainer = Color(0xFFD9E8E1),
    tertiary = Color(0xFFBCC8F2),
    onTertiary = Color(0xFF263153),
    tertiaryContainer = Color(0xFF3D476C),
    onTertiaryContainer = Color(0xFFDBE2FF),
    background = Color(0xFF101514),
    onBackground = Color(0xFFE5ECE8),
    surface = Color(0xFF101514),
    onSurface = Color(0xFFE5ECE8),
    surfaceVariant = Color(0xFF3D4944),
    onSurfaceVariant = Color(0xFFB9C8C0),
    surfaceContainerLowest = Color(0xFF0B100F),
    surfaceContainerLow = Color(0xFF191F1C),
    surfaceContainer = Color(0xFF1D2420),
    surfaceContainerHigh = Color(0xFF27302B),
    surfaceContainerHighest = Color(0xFF303B34),
    outline = Color(0xFF83938A),
    outlineVariant = Color(0xFF35443B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF6D2926),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFAFF4DC),
    onPrimaryContainer = Color(0xFF002B21),
    secondary = Color(0xFF4B6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8DB),
    onSecondaryContainer = Color(0xFF10291E),
    tertiary = Color(0xFF505D8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDEE4FF),
    onTertiaryContainer = Color(0xFF0C1A44),
    background = Color(0xFFF4F8F4),
    onBackground = Color(0xFF17231D),
    surface = Color(0xFFF4F8F4),
    onSurface = Color(0xFF17231D),
    surfaceVariant = Color(0xFFDDE6DE),
    onSurfaceVariant = Color(0xFF44534A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEDF3EC),
    surfaceContainer = Color(0xFFE7EEE6),
    surfaceContainerHigh = Color(0xFFE0E8DF),
    surfaceContainerHighest = Color(0xFFD8E2D7),
    outline = Color(0xFF748378),
    outlineVariant = Color(0xFFC2CFC3),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
)

private val SplitTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

@Composable
fun SplitBoxTheme(settings: AppSettings, content: @Composable (isDark: Boolean) -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val context = LocalContext.current
    val colors = if (settings.dynamicColors) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = SplitTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
    ) { content(dark) }
}
