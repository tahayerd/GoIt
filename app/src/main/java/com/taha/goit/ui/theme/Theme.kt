package com.taha.goit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF0F9D58),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F1F1F),
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF5F6368),
    tertiary = Color(0xFFD93025),
    outline = Color(0xFFBDC1C6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0B3D91),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF81C995),
    onSecondary = Color(0xFF003912),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1E1F22),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF2B2D31),
    onSurfaceVariant = Color(0xFFC4C7C5),
    tertiary = Color(0xFFF28B82),
    outline = Color(0xFF5F6368)
)

/** Saf AMOLED Koyu Tema: Arka plan #000000, batarya tasarrufu ve derin siyahlar */
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF174EA6),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF81C995),
    onSecondary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F3F4),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF1F3F4),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF9AA0A6),
    tertiary = Color(0xFFF28B82),
    outline = Color(0xFF3C4043)
)

@Composable
fun GoItTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val colors = when (themeMode) {
        "amoled" -> AmoledColors
        "dark" -> DarkColors
        "light" -> LightColors
        else -> if (isSystemDark) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
