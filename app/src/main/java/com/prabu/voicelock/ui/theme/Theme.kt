package com.prabu.voicelock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF5B5BD6)
private val IndigoLight = Color(0xFFB9B9F2)
private val LockBackground = Color(0xFF101018)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    background = LockBackground,
    surface = LockBackground,
)

private val LightColors = lightColorScheme(
    primary = Indigo,
)

@Composable
fun VoiceLockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
