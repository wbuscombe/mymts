package com.mymts.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MyMtsDarkColors = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF111111),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF000000),
)

@Composable
fun MyMtsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MyMtsDarkColors,
        content = content,
    )
}
