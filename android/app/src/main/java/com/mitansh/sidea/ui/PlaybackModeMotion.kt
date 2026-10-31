package com.mitansh.sidea.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal data class PlaybackModeKey(val selected: Boolean, val badge: String?)

/** Only a committed mode change, never initial composition or playback ticks, gets a pulse. */
internal object PlaybackModeMotion {
    const val riseMs = 90
    const val settleMs = 210

    fun shouldAnimate(previous: PlaybackModeKey?, next: PlaybackModeKey, motionEnabled: Boolean): Boolean =
        motionEnabled && previous != null && previous != next

    fun rotation(phase: Float): Float = 16f * phase
    fun scale(phase: Float): Float = 1f + .12f * phase
}

internal fun Modifier.playbackModeSemantics(isSelected: Boolean, badge: String?, description: String): Modifier =
    semantics {
        selected = isSelected
        stateDescription = if (badge == "1") "Repeat one" else if (isSelected) "On" else "Off"
        contentDescription = description
    }
