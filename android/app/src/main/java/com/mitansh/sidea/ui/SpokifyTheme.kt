package com.mitansh.sidea.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Spokify's colours.
 *
 * The app is dark by design: album art and the player look better against a near-black surface,
 * and a music player is usually used in the evening. The accent is a single green used for the
 * active state, the play control and the progress, so the interface stays calm.
 */
val SpokifyGreen = Color(0xFF1ED760)
val SpokifyInk = Color(0xFF121212)
val SpokifySurface = Color(0xFF121212)
val SpokifyElevated = Color(0xFF1C1C1C)
val SpokifyMuted = Color(0xFFA7A7A7)
val SpokifyDivider = Color(0xFF2A2A2A)
val SpokifyError = Color(0xFFFFB4AB)

private val SpokifyColors = darkColorScheme(
    primary = SpokifyGreen,
    onPrimary = Color(0xFF06180C),
    background = SpokifyInk,
    onBackground = Color.White,
    surface = SpokifySurface,
    onSurface = Color.White,
    surfaceVariant = SpokifyElevated,
    onSurfaceVariant = SpokifyMuted,
    error = SpokifyError,
)

@Composable
fun SpokifyTheme(content: @Composable () -> Unit) {
    // The app is always dark; the parameter only keeps the call site future proof.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = SpokifyColors, content = content)
}
