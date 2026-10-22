package com.mitansh.sidea.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {

    @Test
    fun `does not record a tap that never actually played`() {
        assertFalse(HistoryPolicy.shouldRecord(accumulatedMs = 0, durationMs = 200_000, completed = false))
    }

    @Test
    fun `does not record a completion that produced almost no playback`() {
        assertFalse(HistoryPolicy.shouldRecord(accumulatedMs = 3_000, durationMs = 200_000, completed = true))
    }

    @Test
    fun `records a long track once 30 seconds have played`() {
        assertFalse(HistoryPolicy.shouldRecord(accumulatedMs = 29_999, durationMs = 300_000, completed = false))
        assertTrue(HistoryPolicy.shouldRecord(accumulatedMs = 30_000, durationMs = 300_000, completed = false))
    }

    @Test
    fun `records a short track once half of it has played`() {
        assertFalse(HistoryPolicy.shouldRecord(accumulatedMs = 19_999, durationMs = 40_000, completed = false))
        assertTrue(HistoryPolicy.shouldRecord(accumulatedMs = 20_000, durationMs = 40_000, completed = false))
    }

    @Test
    fun `records a finished short track after a brief listen`() {
        assertTrue(HistoryPolicy.shouldRecord(accumulatedMs = 6_000, durationMs = 40_000, completed = true))
    }

    @Test
    fun `treats an unknown duration as a long track`() {
        assertFalse(HistoryPolicy.shouldRecord(accumulatedMs = 10_000, durationMs = 0, completed = false))
        assertTrue(HistoryPolicy.shouldRecord(accumulatedMs = 30_000, durationMs = 0, completed = false))
    }

    @Test
    fun `evicts the oldest history entries beyond the limit`() {
        val mostRecentFirst = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "d"), HistoryPolicy.idsToEvict(mostRecentFirst, limit = 2))
    }

    @Test
    fun `keeps every entry while under the limit`() {
        assertEquals(emptyList<String>(), HistoryPolicy.idsToEvict(listOf("a", "b"), limit = 50))
    }
}
