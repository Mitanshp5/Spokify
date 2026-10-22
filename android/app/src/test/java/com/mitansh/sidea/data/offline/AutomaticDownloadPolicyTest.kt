package com.mitansh.sidea.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticDownloadPolicyTest {

    @Test
    fun qualifiesOnlyOncePlayCountReachesThreshold() {
        assertFalse(AutomaticDownloadPolicy.isQualified(0))
        assertFalse(AutomaticDownloadPolicy.isQualified(2))
        assertTrue(AutomaticDownloadPolicy.isQualified(3))
        assertTrue(AutomaticDownloadPolicy.isQualified(9))
    }

    @Test
    fun suppressedItemIsNotRequeuedAtTheSamePlayCount() {
        val suppressedAt = 3
        assertFalse(AutomaticDownloadPolicy.canRequeueOnPlay(3, suppressedAt))
        assertTrue(AutomaticDownloadPolicy.canRequeueOnPlay(4, suppressedAt))
    }

    @Test
    fun neverSuppressedItemCanAlwaysRequeue() {
        assertTrue(AutomaticDownloadPolicy.canRequeueOnPlay(3, 0))
    }

    @Test
    fun accountsForReservationsWhenDecidingCapacity() {
        val roomy = AutomaticUsage(storedBytes = 100L, inFlightBytes = 200L, pendingCount = 1, trackedCount = 1)
        assertTrue(AutomaticDownloadPolicy.canQueue(roomy, capBytes = AutomaticDownloadPolicy.CAP_BYTES))

        val full = AutomaticUsage(
            storedBytes = AutomaticDownloadPolicy.CAP_BYTES,
            inFlightBytes = 0,
            pendingCount = 0,
            trackedCount = 5,
        )
        assertFalse(AutomaticDownloadPolicy.canQueue(full, capBytes = AutomaticDownloadPolicy.CAP_BYTES))
    }

    @Test
    fun pendingReservationsCountTowardTheCap() {
        val reservation = AutomaticDownloadPolicy.RESERVATION_BYTES
        // Existing reservations plus the incoming item land exactly on the cap: still accepted.
        val exactlyAtCap = AutomaticUsage(
            storedBytes = AutomaticDownloadPolicy.CAP_BYTES - 2 * reservation,
            inFlightBytes = 0,
            pendingCount = 1,
            trackedCount = 1,
        )
        assertTrue(AutomaticDownloadPolicy.canQueue(exactlyAtCap, capBytes = AutomaticDownloadPolicy.CAP_BYTES))
        // One more existing reservation pushes the incoming item over the cap.
        assertFalse(AutomaticDownloadPolicy.canQueue(exactlyAtCap.copy(pendingCount = 2), capBytes = AutomaticDownloadPolicy.CAP_BYTES))
    }

    @Test
    fun projectedBytesIncludesInFlightAndReservations() {
        val usage = AutomaticUsage(storedBytes = 10, inFlightBytes = 20, pendingCount = 3, trackedCount = 3)
        assertEquals(
            10 + 20 + 3 * AutomaticDownloadPolicy.RESERVATION_BYTES,
            AutomaticDownloadPolicy.projectedBytes(usage, incomingBytes = 0),
        )
    }

    @Test
    fun trackedLimitStopsUnboundedQueue() {
        val many = AutomaticUsage(
            storedBytes = 0,
            inFlightBytes = 0,
            pendingCount = 0,
            trackedCount = AutomaticDownloadPolicy.MAX_TRACKED_AUTOMATIC,
        )
        assertFalse(AutomaticDownloadPolicy.canQueue(many, capBytes = AutomaticDownloadPolicy.CAP_BYTES))
    }

    @Test
    fun disablingCancelsOnlyPendingAutomaticJobs() {
        assertTrue(AutomaticDownloadPolicy.shouldCancelOnDisable(DownloadState.STATE_DOWNLOADING, automatic = true))
        assertTrue(AutomaticDownloadPolicy.shouldCancelOnDisable(DownloadState.STATE_QUEUED, automatic = true))
        assertTrue(AutomaticDownloadPolicy.shouldCancelOnDisable(DownloadState.STATE_STOPPED, automatic = true))
        assertFalse(AutomaticDownloadPolicy.shouldCancelOnDisable(DownloadState.STATE_COMPLETED, automatic = true))
        assertFalse(AutomaticDownloadPolicy.shouldCancelOnDisable(DownloadState.STATE_DOWNLOADING, automatic = false))
    }

    @Test
    fun theBudgetIsTheUsersChoiceRatherThanAFixedValue() {
        val usage = AutomaticUsage(
            storedBytes = AutomaticDownloadPolicy.CAP_BYTES,
            inFlightBytes = 0,
            pendingCount = 0,
            trackedCount = 5,
        )
        assertFalse(AutomaticDownloadPolicy.canQueue(usage, capBytes = AutomaticDownloadPolicy.CAP_BYTES))

        val larger = usage.copy(storedBytes = AutomaticDownloadPolicy.CAP_BYTES)
        assertTrue(
            "expected a bigger budget to accept the item",
            AutomaticDownloadPolicy.canQueue(larger, capBytes = AutomaticDownloadPolicy.CAP_BYTES * 4),
        )
    }

    @Test
    fun aSmallerBudgetRefusesAnItemThatTheDefaultWouldAccept() {
        val usage = AutomaticUsage(storedBytes = 200L * 1024 * 1024, trackedCount = 1)
        assertTrue(AutomaticDownloadPolicy.canQueue(usage, capBytes = AutomaticDownloadPolicy.CAP_BYTES))
        assertFalse(AutomaticDownloadPolicy.canQueue(usage, capBytes = 100L * 1024 * 1024))
    }
}
