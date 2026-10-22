package com.mitansh.sidea.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class QuietNotificationSessionTest {
    @Test fun newProcessClearsAnOldRuleBeforeAnyPlayback() {
        val writes = mutableListOf<Boolean>()
        QuietNotificationSession { writes += it }.update(false, true, true)
        assertEquals(listOf(false), writes)
    }

    @Test fun playbackPauseAndReplayDoNotRepeatedlyReactivateTheRule() {
        val writes = mutableListOf<Boolean>()
        val session = QuietNotificationSession { writes += it }
        session.update(false, true, true)
        repeat(20) { session.update(true, true, true) }
        repeat(20) { session.update(false, true, true) }
        session.update(true, true, true)
        assertEquals(listOf(false, true, false, true), writes)
    }

    @Test fun revocationDoesNotWriteWithoutAccessAndRegrantUsesCurrentPlaybackState() {
        val writes = mutableListOf<Boolean>()
        val session = QuietNotificationSession { writes += it }
        session.update(true, true, true)
        session.update(true, true, false)
        session.update(false, true, false)
        assertEquals(listOf(true), writes)
        session.update(false, true, true)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun disablingWhilePlayingReleasesOnlyTheOwnedQuietRule() {
        val writes = mutableListOf<Boolean>()
        val session = QuietNotificationSession { writes += it }
        session.update(true, true, true)
        session.update(true, false, true)
        session.update(true, false, true)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun failedActivationIsRetriedRatherThanCachedAsSuccessful() {
        val writes = mutableListOf<Boolean>()
        var fail = true
        val session = QuietNotificationSession {
            if (fail) throw IllegalStateException("Policy access changed")
            writes += it
        }
        try { session.update(true, true, true); fail("write should fail") } catch (_: IllegalStateException) { }
        assertTrue(writes.isEmpty())
        fail = false
        session.update(true, true, true)
        session.update(false, true, true)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun failedCleanupIsRetried() {
        val writes = mutableListOf<Boolean>()
        var failCleanup = true
        val session = QuietNotificationSession {
            if (!it && failCleanup) throw IllegalStateException("Temporary Android failure")
            writes += it
        }
        session.update(true, true, true)
        try { session.update(false, true, true); fail("cleanup should fail") } catch (_: IllegalStateException) { }
        failCleanup = false
        session.update(false, true, true)
        assertEquals(listOf(true, false), writes)
    }

    @Test fun bufferingKeepsQuietButPauseFocusLossErrorsAndQueueEndReleaseIt() {
        val writes = mutableListOf<Boolean>()
        val session = QuietNotificationSession { writes += it }
        fun player(ready: Boolean, state: Int, count: Int = 1, suppression: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            session.update(PlaybackQuietPolicy.isActive(ready, count, state, suppression), true, true)
        }
        player(true, Player.STATE_READY)
        player(true, Player.STATE_BUFFERING)
        player(false, Player.STATE_BUFFERING) // User pauses during buffering.
        player(true, Player.STATE_READY)
        player(true, Player.STATE_READY, suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
        player(true, Player.STATE_BUFFERING)
        player(true, Player.STATE_IDLE) // Error or stop.
        player(true, Player.STATE_READY)
        player(true, Player.STATE_ENDED)
        player(true, Player.STATE_READY, count = 0)
        assertEquals(listOf(true, false, true, false, true, false, true, false), writes)
    }
}
