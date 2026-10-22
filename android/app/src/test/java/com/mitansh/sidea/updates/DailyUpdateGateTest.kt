package com.mitansh.sidea.updates

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DailyUpdateGateTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test fun claimsOnlyFirstOpenOnSameCalendarDay() {
        var stored: String? = null
        var writes = 0
        val gate = DailyUpdateGate({ stored }, { stored = it; writes++; true })
        assertTrue(gate.claim(today))
        repeat(10) { assertFalse(gate.claim(today)) }
        assertEquals(1, writes)
    }
    @Test fun persistedAttemptSurvivesNewControllerAndFailedNetworkRequest() {
        var stored: String? = null
        fun controller() = DailyUpdateGate({ stored }, { stored = it; true })
        assertTrue(controller().claim(today))
        // A timeout does not reset the attempt; reopening must not retry automatically.
        assertFalse(controller().claim(today))
    }
    @Test fun nextDayAllowsExactlyOneNewAttempt() {
        var stored: String? = today.toString()
        val gate = DailyUpdateGate({ stored }, { stored = it; true })
        assertFalse(gate.claim(today))
        assertTrue(gate.claim(today.plusDays(1)))
        assertFalse(gate.claim(today.plusDays(1)))
    }
    @Test fun usesCalendarBoundaryRatherThanElapsed24Hours() {
        var stored: String? = LocalDate.of(2026, 12, 31).toString()
        val gate = DailyUpdateGate({ stored }, { stored = it; true })
        assertTrue(gate.claim(LocalDate.of(2027, 1, 1)))
    }
    @Test fun storageFailureDoesNotClaimAnAttempt() {
        val gate = DailyUpdateGate({ null }, { false })
        assertFalse(gate.claim(today))
    }
    @Test fun concurrentActivityInstancesShareOneClaim() {
        val stored = AtomicReference<String?>()
        val claims = AtomicInteger()
        val workers = (1..12).map { Thread {
            val gate = DailyUpdateGate(stored::get, { stored.set(it); true })
            if (gate.claim(today)) claims.incrementAndGet()
        }.apply { start() } }
        workers.forEach { it.join() }
        assertEquals(1, claims.get())
    }
}
