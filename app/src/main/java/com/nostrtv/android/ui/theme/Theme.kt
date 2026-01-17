package com.nostrtv.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
private val DarkColorScheme = darkColorScheme(
    primary = Purple500,
    onPrimary = White,
    secondary = Purple700,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = White,
    onSurface = White
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NostrTVTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
