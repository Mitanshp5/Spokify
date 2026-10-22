package com.mitansh.sidea.playback

import androidx.media3.common.Player

internal object PlaybackQuietPolicy {
    fun isActive(playWhenReady: Boolean, itemCount: Int, state: Int, suppression: Int): Boolean =
        playWhenReady && itemCount > 0 && state in setOf(Player.STATE_READY, Player.STATE_BUFFERING) &&
            suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE
}

/** Avoid repeatedly reactivating a rule the listener has snoozed in Android. */
internal class QuietNotificationSession(private val apply: (Boolean) -> Unit) {
    private var applied: Boolean? = null

    fun update(playbackActive: Boolean, enabled: Boolean, permitted: Boolean) {
        if (!permitted) {
            applied = null
            return
        }
        val quiet = playbackActive && enabled
        if (applied != quiet) {
            apply(quiet)
            // Failed writes must be retried instead of reporting a successful activation.
            applied = quiet
        }
    }
}
