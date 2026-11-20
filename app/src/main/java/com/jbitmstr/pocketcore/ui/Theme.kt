package com.jbitmstr.pocketcore.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Midnight navy palette: deep blue-black surfaces with a cyan-blue accent, the
 * console look of a node running on the network rather than a warm wallet UI.
 */
object PocketColors {
    val Background = Color(0xFF050912)
    val Card = Color(0xFF0B1220)
    val CardElevated = Color(0xFF111A2E)
    val Hairline = Color(0xFF1E293B)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFF94A3B8)
    val Faint = Color(0xFF64748B)
    val Accent = Color(0xFF38BDF8)
    val AccentDeep = Color(0xFF3B82F6)
    val OnAccent = Color(0xFF04121F)
    val Running = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
}

val Mono = FontFamily.Monospace

private val DarkScheme = darkColorScheme(
    primary = PocketColors.Accent,
    onPrimary = PocketColors.OnAccent,
    secondary = PocketColors.AccentDeep,
    onSecondary = Color.White,
    background = PocketColors.Background,
    onBackground = PocketColors.Text,
    surface = PocketColors.Card,
    onSurface = PocketColors.Text,
    surfaceVariant = PocketColors.CardElevated,
    onSurfaceVariant = PocketColors.Muted,
    outline = PocketColors.Hairline,
    error = PocketColors.Error,
    onError = PocketColors.OnAccent
)

@Composable
fun PocketCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content
    )
}
