package com.agendek.bramav2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Teal200,
    onPrimary = Navy900,
    primaryContainer = Color(0xFF0F4F57),
    onPrimaryContainer = Color(0xFFCFF3F6),
    secondary = Navy200,
    onSecondary = Navy900,
    secondaryContainer = Color(0xFF2A4A5E),
    onSecondaryContainer = Color(0xFFDCEAF2),
    tertiary = Amber500,
    tertiaryContainer = Color(0xFF5A4410),
    onTertiaryContainer = Color(0xFFFFE3A6),
    background = SurfaceDark,
    onBackground = Color(0xFFE4EDF2),
    surface = Color(0xFF14262F),
    onSurface = Color(0xFFE4EDF2),
    surfaceVariant = Color(0xFF1D3441),
    onSurfaceVariant = Navy200,
    error = Color(0xFFFF8A80),
    outline = Color(0xFF6E8796),
    outlineVariant = Color(0xFF34505F),
)

private val LightColorScheme = lightColorScheme(
    primary = Teal600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EBEE),
    onPrimaryContainer = Color(0xFF05353B),
    secondary = Navy700,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E4EE),
    onSecondaryContainer = Navy900,
    tertiary = Amber500,
    tertiaryContainer = Color(0xFFFFE3A6),
    onTertiaryContainer = Color(0xFF3D2B00),
    background = SurfaceLight,
    onBackground = Navy900,
    surface = Color.White,
    onSurface = Navy900,
    surfaceVariant = Color(0xFFE1ECF1),
    onSurfaceVariant = Navy700,
    error = Coral600,
    outline = Color(0xFF7A8F9C),
    outlineVariant = Color(0xFFC9D8E0),
)

/** Motyw marki: stałe kolory (bez dynamicznych barw systemu), jasny i ciemny. */
@Composable
fun BramaV2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
