package com.mitansh.sidea.playback

import org.junit.Assert.*
import org.junit.Test

class SleepTimerEndOfTrackTest {
    private var now = 0L
    private var pauses = 0
    private var guard = false
    private val guardWrites = mutableListOf<Boolean>()
    private val queue = listOf("current", "next")
    private val volume = 0.75f
    private val timer = SleepTimerController({ now }, { pauses++ }, {}, { guard }, {
        guard = it
        guardWrites.add(it)
    })

    @Test fun armsBoundaryGuardAndCompletesOnceWithoutChangingQueueOrVolume() {
        assertTrue(timer.command("end_of_track"))
        assertTrue(guard)
        timer.onTrackEnded() // Media3 END_OF_MEDIA_ITEM or STATE_ENDED, including seek-to-end.
        timer.onTrackEnded()
        timer.onTrackTransition(automatic = true)
        assertEquals(1, pauses)
        assertEquals("off", timer.state.mode)
        assertFalse(guard)
        assertEquals(listOf(true, false), guardWrites)
        assertEquals(listOf("current", "next"), queue)
        assertEquals(0.75f, volume)
    }

    @Test fun repeatOneOrAutomaticNextBoundaryCompletesOnce() {
        for (boundary in listOf("repeat", "auto")) {
            assertTrue(timer.command("end_of_track"))
            timer.onTrackTransition(automatic = true)
            timer.onTrackEnded()
            assertEquals("off", timer.state.mode)
        }
        assertEquals(2, pauses)
    }

    @Test fun manualNextPreviousOrPlaylistReplacementCancelsWithoutPause() {
        timer.command("end_of_track")
        timer.onTrackTransition(automatic = false)
        timer.onTrackEnded()
        assertEquals(0, pauses)
        assertEquals("off", timer.state.mode)
        assertFalse(guard)
    }

    @Test fun cancelRestoresPreexistingTrueBoundarySetting() {
        guard = true
        timer.command("end_of_track")
        timer.command("end_of_track")
        timer.command("off")
        assertTrue(guard)
        assertEquals(listOf(true, true), guardWrites)
        assertEquals(0, pauses)
    }

    @Test fun switchFromEndToDurationRestoresBoundaryAndIgnoresOldEndEvent() {
        timer.command("end_of_track")
        timer.command("duration", 1_000)
        assertFalse(guard)
        timer.onTrackEnded()
        timer.onTrackTransition(automatic = false)
        assertEquals("duration", timer.state.mode)
        now = 1_000
        timer.tick()
        assertEquals(1, pauses)
    }

    @Test fun switchFromDurationToEndDisarmsOldDeadline() {
        timer.command("duration", 1_000)
        timer.command("end_of_track")
        now = 2_000
        timer.tick()
        assertEquals(0, pauses)
        timer.onTrackEnded()
        assertEquals(1, pauses)
    }

    @Test fun missingCurrentTrackDoesNotReplaceExistingDuration() {
        timer.command("duration", 1_000)
        val active = timer.state
        assertFalse(timer.command("end_of_track", hasCurrentTrack = false))
        assertEquals(active, timer.state)
        assertFalse(guard)
    }

    @Test fun serviceDestroyRestoresBoundaryWithoutPausing() {
        timer.command("end_of_track")
        timer.close()
        timer.onTrackEnded()
        assertFalse(guard)
        assertEquals(0, pauses)
    }

    @Test fun completionClearsStateBeforeReentrantPauseCallback() {
        var count = 0
        lateinit var reentrant: SleepTimerController
        reentrant = SleepTimerController({ 0 }, { count++; reentrant.onTrackEnded() }, {})
        reentrant.command("end_of_track")
        reentrant.onTrackEnded()
        assertEquals(1, count)
    }
}
