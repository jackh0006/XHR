package com.jackh0006.xhr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object XhrColors {
    val Blue = Color(0xFF4285F4)
    val BlueDark = Color(0xFF1A73E8)
    val Red = Color(0xFFEA4335)
    val Yellow = Color(0xFFFBBC05)
    val Green = Color(0xFF34A853)
    val Ink = Color(0xFF18202F)
    val Muted = Color(0xFF687386)
    val Line = Color(0xFFE6EAF2)
    val Bg = Color(0xFFF6F8FC)
    val Card = Color(0xFFFFFFFF)
    val Night = Color(0xFF0B1020)
    val NightCard = Color(0xFF141B2E)
}

private val Light = lightColorScheme(
    primary = XhrColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B2B62),
    secondary = XhrColors.Green,
    onSecondary = Color.White,
    tertiary = XhrColors.Yellow,
    onTertiary = Color(0xFF3A2C00),
    error = XhrColors.Red,
    background = XhrColors.Bg,
    onBackground = XhrColors.Ink,
    surface = XhrColors.Card,
    onSurface = XhrColors.Ink,
    surfaceVariant = Color(0xFFEEF2F9),
    onSurfaceVariant = XhrColors.Muted,
    outline = XhrColors.Line,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062A62),
    primaryContainer = Color(0xFF1C3766),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF81C995),
    onSecondary = Color(0xFF06351A),
    tertiary = Color(0xFFFDD663),
    onTertiary = Color(0xFF3A2C00),
    error = Color(0xFFFF8A80),
    background = XhrColors.Night,
    onBackground = Color(0xFFF2F5FA),
    surface = XhrColors.NightCard,
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF1E2740),
    onSurfaceVariant = Color(0xFFB8C2D6),
    outline = Color(0xFF2C3855),
)

private val XhrTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 0.4.sp,
    ),
)

private val XhrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun XhrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = XhrTypography,
        shapes = XhrShapes,
        content = content,
    )
}
