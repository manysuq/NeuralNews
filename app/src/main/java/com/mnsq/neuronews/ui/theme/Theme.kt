package com.mnsq.neuronews.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val NewsColors = darkColorScheme(
    primary = Red,
    onPrimary = Paper,
    primaryContainer = RedDark,
    onPrimaryContainer = Paper,
    secondary = Paper,
    onSecondary = PaperInk,
    background = Ink,
    onBackground = Paper,
    surface = InkCard,
    onSurface = Paper,
    surfaceVariant = InkCard,
    onSurfaceVariant = Muted,
    outline = Stroke,
    outlineVariant = Stroke,
    error = Red,
)

private val NewsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        letterSpacing = 1.5.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun NeuralNewsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NewsColors,
        typography = NewsTypography,
        content = content,
    )
}
