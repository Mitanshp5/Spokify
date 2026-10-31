package com.mitansh.sidea.ui

import com.mitansh.sidea.playback.SleepTimerCommandDispatcher
import com.mitansh.sidea.playback.SleepTimerCommands
import com.mitansh.sidea.playback.SleepTimerController
import org.junit.Assert.*
import org.junit.Test

/** Characterizes the accepted foundation through the same dispatcher and UI mapping used in production. */
class SleepTimerFoundationTest {
    private var now = 10_000L
    private var pauses = 0
    private var pauseAtEnd = false
    private val timer = SleepTimerController({ now }, { pauses++ }, {}, { pauseAtEnd }, { pauseAtEnd = it })
    private val dispatcher = SleepTimerCommandDispatcher(timer)
    private val requests = SleepTimerRequests()

    private fun command(mode: String? = null, minutes: Int? = null, hasTrack: Boolean = true): SleepTimerCommandDispatcher.Result {
        val token = requests.begin(mode != null)!!
        val result = dispatcher.dispatch(
            if (mode == null) SleepTimerCommands.ACTION_GET else SleepTimerCommands.ACTION_SET,
            true, mode, minutes?.times(60_000L), hasTrack,
        )
        val snapshot = sleepTimerStatus(timer.state.mode, timer.state.deadlineElapsedRealtimeMs,
            timer.remainingMs(), timer.state.mode == "end_of_track")
        requests.complete(token, result == SleepTimerCommandDispatcher.Result.SUCCESS, snapshot,
            if (result == SleepTimerCommandDispatcher.Result.SUCCESS) null else "Service rejected request")
        return result
    }

    @Test fun oneMinuteCountdownExpiresInServiceWithoutUiTicks() {
        command("duration", 1)
        assertEquals("Stops in 1:00", requests.state.summary)
        now += 1001L
        command()
        assertEquals("Stops in 0:59", requests.state.summary)
        now += 58_999L
        timer.tick() // No Activity/observer present while service expires.
        assertEquals(1, pauses)
        command()
        assertEquals("Sleep timer off", requests.state.summary)
    }

    @Test fun replacementAndCancelUseTheReturnedState() {
        command("duration", 15)
        now += 20_000L
        command("duration", 30)
        assertEquals("Stops in 30:00", requests.state.summary)
        command("off")
        assertFalse(requests.state.status!!.active)
        now += 3_600_000L
        command()
        assertEquals(0, pauses)
    }

    @Test fun endOfSongGuardsBoundaryAndRestoresOnCompletion() {
        command("end_of_track")
        assertTrue(pauseAtEnd)
        assertEquals("Stops after this song", requests.state.summary)
        timer.onTrackEnded()
        timer.onTrackTransition(true)
        command()
        assertEquals(1, pauses)
        assertFalse(pauseAtEnd)
        assertFalse(requests.state.status!!.active)
    }

    @Test fun manualNextCancelsEndOfSongWithoutPausingNewSong() {
        command("end_of_track")
        timer.onTrackTransition(false)
        command()
        assertEquals("Sleep timer off", requests.state.summary)
        assertFalse(pauseAtEnd)
        assertEquals(0, pauses)
    }

    @Test fun rejectedEndOfSongKeepsDurationAndReportsError() {
        command("duration", 15)
        assertEquals(SleepTimerCommandDispatcher.Result.INVALID_STATE, command("end_of_track", hasTrack = false))
        assertEquals("Stops in 15:00", requests.state.summary)
        assertNotNull(requests.state.error)
        assertEquals(0, pauses)
    }

    @Test fun invalidOrUntrustedCommandsDoNotReplaceTimer() {
        command("duration", 15)
        val previous = timer.state
        assertEquals(SleepTimerCommandDispatcher.Result.BAD_VALUE,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, "duration", 0L))
        assertEquals(SleepTimerCommandDispatcher.Result.PERMISSION_DENIED,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, false, "off"))
        assertEquals(previous, timer.state)
    }
}
