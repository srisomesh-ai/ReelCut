package com.srisomesh.reelcut.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2563C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FB),
    onPrimaryContainer = Color(0xFF123B7A),
    background = Color(0xFFF5F4F0),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFEDE7),
    onSurfaceVariant = Color(0xFF5F5E5A),
    outline = Color(0xFFCFCCC3),
    outlineVariant = Color(0xFFE2DFD7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DA9F0),
    onPrimary = Color(0xFF0B2350),
    primaryContainer = Color(0xFF1B3561),
    onPrimaryContainer = Color(0xFFDCE8FB),
    background = Color(0xFF151513),
    onBackground = Color(0xFFEDEBE5),
    surface = Color(0xFF1F1E1B),
    onSurface = Color(0xFFEDEBE5),
    surfaceVariant = Color(0xFF292825),
    onSurfaceVariant = Color(0xFFB4B2A9),
    outline = Color(0xFF4A4945),
    outlineVariant = Color(0xFF3A3936),
)

private val Type = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ReelCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
