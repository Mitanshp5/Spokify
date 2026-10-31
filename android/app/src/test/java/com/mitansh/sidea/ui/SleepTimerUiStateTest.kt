package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class SleepTimerUiStateTest {
    @Test fun offAndEndOfSongAreAuthoritativeDistinctStates() {
        val off = sleepTimerStatus("off", 0L, 0L, false)!!
        assertFalse(off.active)
        assertEquals("Sleep timer off", off.summary)
        val end = sleepTimerStatus("end_of_track", 0L, 0L, true)!!
        assertTrue(end.active)
        assertEquals("Stops after this song", end.summary)
        assertNull(sleepTimerStatus("off", 0L, 1L, false))
        assertNull(sleepTimerStatus("end_of_track", 0L, 0L, false))
    }

    @Test fun durationSummaryRoundsUpWithoutPrematureZero() {
        assertEquals("Stops in 15:00", SleepTimerStatus("duration", 899_001L).summary)
        assertEquals("Stops in 14:59", SleepTimerStatus("duration", 899_000L).summary)
        assertEquals("Stops in 0:01", SleepTimerStatus("duration", 1L).summary)
        assertEquals("Stops in 0:01", SleepTimerStatus("duration", 0L).summary)
        assertEquals("Stops in 180:00", SleepTimerStatus("duration", 10_800_000L).summary)
        assertTrue(SleepTimerStatus("duration", 1000L).active)
    }

    @Test fun durationStatusComesFromTypedServiceSnapshot() {
        assertEquals(SleepTimerStatus("duration", 899_001L), sleepTimerStatus("duration", 1_000_000L, 899_001L, false))
        assertNull(sleepTimerStatus("future_mode", 0L, 0L, false))
        assertNull(sleepTimerStatus(null, null, null, null))
        assertNull(sleepTimerStatus("duration", 1000, 999L, false))
        assertNull(sleepTimerStatus("duration", 1000L, -1L, false))
        assertNull(sleepTimerStatus("duration", 0L, 999L, false))
        assertNull(sleepTimerStatus("duration", 1000L, 999L, true))
    }
}
