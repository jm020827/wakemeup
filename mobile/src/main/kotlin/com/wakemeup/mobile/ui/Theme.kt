package com.wakemeup.mobile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Night = Color(0xFF101625)
val Panel = Color(0xFF1C2538)
val Lavender = Color(0xFFC0B8FF)
val Dawn = Color(0xFFFFD49E)
val Muted = Color(0xFF9EAAC0)

@Composable fun WakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Night, secondary = Dawn, background = Night,
        surface = Panel, onSurface = Color(0xFFF3F2FC), onBackground = Color(0xFFF3F2FC), outline = Muted, error = Color(0xFFFFB4AB)), content = content)
}
