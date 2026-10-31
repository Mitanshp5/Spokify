package com.mitansh.sidea.playback

import org.junit.Assert.*
import org.junit.Test

class SleepTimerControllerTest {
    private var now = 50_000L
    private var pauses = 0
    private val transitions = mutableListOf<SleepTimerController.State>()
    private val timer = SleepTimerController({ now }, { pauses++ }, transitions::add)

    @Test fun durationPausesAtExactMonotonicDeadlineExactlyOnceWithoutActivity() {
        assertTrue(timer.command("duration", 1_000))
        assertEquals(51_000L, timer.state.deadlineElapsedRealtimeMs)
        now = 50_999
        timer.tick()
        assertEquals(0, pauses)
        now = 51_000
        timer.tick()
        timer.tick()
        assertEquals(1, pauses)
        assertEquals("off", timer.state.mode)
        assertEquals(listOf("duration", "off"), transitions.map { it.mode })
    }

    @Test fun cancelIsIdempotentAndNeverPausesAtOldDeadline() {
        timer.command("duration", 1_000)
        assertTrue(timer.command("off"))
        assertTrue(timer.command("off"))
        now += 10_000
        timer.tick()
        assertEquals(0, pauses)
        assertEquals(2, transitions.size)
    }

    @Test fun replacingDurationCannotFireOldDeadline() {
        timer.command("duration", 1_000)
        now += 500
        timer.command("duration", 2_000)
        now += 500
        timer.tick()
        assertEquals(0, pauses)
        assertEquals(1_500L, timer.remainingMs())
        now += 1_500
        timer.tick()
        assertEquals(1, pauses)
        assertEquals(0L, timer.remainingMs())
    }

    @Test fun invalidModesAndDurationBoundsPreserveActiveTimer() {
        assertTrue(timer.command("duration", 86_400_000))
        val active = timer.state
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 999L, 86_400_001L, Long.MAX_VALUE)) {
            assertFalse(timer.command("duration", duration))
            assertEquals(active, timer.state)
        }
        assertFalse(timer.command("unknown", 1_000))
        assertEquals(active, timer.state)
    }

    @Test fun elapsedDurationSurvivesNoObserversOrPlaybackProgress() {
        // No Activity, lifecycle owner, playback position, or wall clock is an input.
        timer.command("duration", 15 * 60_000)
        now += 15 * 60_000 + 12_345
        timer.tick()
        assertEquals(1, pauses)
        assertEquals("off", timer.state.mode)
    }

    @Test fun closeClearsTimerWithoutPausingAndRejectsNewCommands() {
        timer.command("duration", 1_000)
        timer.close()
        now += 2_000
        timer.tick()
        assertEquals("off", timer.state.mode)
        assertEquals(0, pauses)
        assertFalse(timer.command("duration", 1_000))
    }
}
