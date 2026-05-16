package com.example.ridescope.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF69D3FF),
    secondary = Color(0xFF83F76A),
    tertiary = Color(0xFFFFD54F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    background = Color(0xFF090B0F),
    onBackground = Color.White,
    surface = Color(0xFF0E1115),
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005D7A),
    secondary = Color(0xFF2A6A00),
    tertiary = Color(0xFF7A5A00),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    background = Color(0xFF090B0F),
    onBackground = Color.White,
    surface = Color(0xFF0E1115),
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
)

@Composable
fun RideScopeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
