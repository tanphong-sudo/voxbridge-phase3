package com.voxbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Light primary UI; saturated safety fields retain their explicit field colors and white text. */
@Composable
fun VoxBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Trust.Green,
            error = Color(0xFFBA1A1A),
            background = Trust.Background,
            surface = Trust.Surface,
            onPrimary = Color.White,
            onBackground = Trust.Ink,
            onSurface = Trust.Ink,
        ),
        typography = OneVoiceTypography,
        content = content,
    )
}
