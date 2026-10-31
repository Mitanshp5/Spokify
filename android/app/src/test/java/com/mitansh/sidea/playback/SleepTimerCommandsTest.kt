package com.mitansh.sidea.playback

import org.junit.Assert.*
import org.junit.Test

class SleepTimerCommandsTest {
    private var now = 100L
    private var pauses = 0
    private val timer = SleepTimerController({ now }, { pauses++ }, {})
    private val dispatcher = SleepTimerCommandDispatcher(timer)

    @Test fun setGetAndOffRunThroughProductionDispatcher() {
        assertEquals(SleepTimerCommandDispatcher.Result.SUCCESS,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, "duration", 1_000L))
        assertEquals(1_100L, timer.state.deadlineElapsedRealtimeMs)
        now = 600
        assertEquals(SleepTimerCommandDispatcher.Result.SUCCESS,
            dispatcher.dispatch(SleepTimerCommands.ACTION_GET, true))
        assertEquals(500L, timer.remainingMs())
        assertEquals(SleepTimerCommandDispatcher.Result.SUCCESS,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, "off"))
        assertEquals("off", timer.state.mode)
        assertEquals(0, pauses)
    }

    @Test fun malformedArgsReturnBadValueWithoutChangingActiveState() {
        timer.command("duration", 30_000)
        val active = timer.state
        for ((mode, duration) in listOf(null to 1_000L, 1 to 1_000L, "bogus" to 1_000L,
            "duration" to null, "duration" to 1_000, "duration" to "1000",
            "duration" to 999L, "duration" to Long.MAX_VALUE)) {
            assertEquals(SleepTimerCommandDispatcher.Result.BAD_VALUE,
                dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, mode, duration))
            assertEquals(active, timer.state)
        }
    }

    @Test fun untrustedControllersCannotSetCancelOrQuery() {
        timer.command("duration", 30_000)
        val active = timer.state
        for (action in listOf(SleepTimerCommands.ACTION_SET, SleepTimerCommands.ACTION_GET)) {
            assertEquals(SleepTimerCommandDispatcher.Result.PERMISSION_DENIED,
                dispatcher.dispatch(action, false, "off"))
            assertEquals(active, timer.state)
        }
    }

    @Test fun endOfTrackRequiresAnActualCurrentTrack() {
        timer.command("duration", 30_000)
        val active = timer.state
        assertEquals(SleepTimerCommandDispatcher.Result.INVALID_STATE,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, "end_of_track", hasCurrentTrack = false))
        assertEquals(active, timer.state)
        assertEquals(SleepTimerCommandDispatcher.Result.SUCCESS,
            dispatcher.dispatch(SleepTimerCommands.ACTION_SET, true, "end_of_track"))
        assertEquals("end_of_track", timer.state.mode)
    }

    @Test fun getRefreshesElapsedDeadlineWithoutAnyActivityObserver() {
        timer.command("duration", 1_000)
        now = 5_000
        assertEquals(SleepTimerCommandDispatcher.Result.SUCCESS,
            dispatcher.dispatch(SleepTimerCommands.ACTION_GET, true))
        assertEquals("off", timer.state.mode)
        assertEquals(1, pauses)
    }

    @Test fun unrelatedCommandIsNotHandled() {
        assertEquals(SleepTimerCommandDispatcher.Result.NOT_SUPPORTED,
            dispatcher.dispatch("other", true))
    }
}
