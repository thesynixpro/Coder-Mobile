package com.aprax.coderm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    secondary = Color(0xFF60E7F2),
    tertiary = Color(0xFF9B8CFF),
    background = Color(0xFF0B0E13),
    surface = Color(0xFF10141B),
    surfaceVariant = Color(0xFF1A2029)
)
private val Light = lightColorScheme(
    primary = Color(0xFF315FBA),
    secondary = Color(0xFF006A73),
    tertiary = Color(0xFF5C4FA6),
    background = Color(0xFFF8F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECF3)
)

@Composable
fun CoderMobileTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
