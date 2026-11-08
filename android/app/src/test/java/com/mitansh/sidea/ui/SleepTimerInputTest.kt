package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class SleepTimerInputTest {
    @Test fun customMinutesAcceptsOnlyVerbatimWholeMinutesInRange() {
        for (minutes in 1..180) assertEquals(minutes, sleepTimerCustomMinutes(minutes.toString()))
        for (input in listOf("", "0", "181", "-1", "+1", " 1", "1 ", "1.5", "1e2", "999999999999999999999", "abc", "١")) {
            assertNull("Must reject '$input' without coercing it", sleepTimerCustomMinutes(input))
        }
        assertEquals(listOf(5, 10, 15, 30, 45, 60), sleepTimerPresets)
        for (minutes in sleepTimerPresets) assertEquals(minutes, sleepTimerCustomMinutes(minutes.toString()))
    }
}
