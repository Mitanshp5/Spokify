package com.mitansh.sidea.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the budget handling the production code uses.
 *
 * The budget and the size of an incoming item are different values, and a call that passes one
 * where the other belongs silently changes what the user asked for. These cases use the same
 * argument shape the repository uses.
 */
class AutomaticBudgetTest {

    private val megabyte = 1024L * 1024L

    @Test
    fun `the selected budget is the ceiling, not the size of the incoming item`() {
        val usage = AutomaticUsage(storedBytes = 0, inFlightBytes = 0, pendingCount = 0, trackedCount = 0)

        // A 250 MiB budget must refuse an item that would push the total past 250 MiB.
        assertTrue(
            "expected the 250 MiB budget to be the ceiling",
            AutomaticDownloadPolicy.canQueue(usage, capBytes = 250 * megabyte),
        )
        val nearlyFull = usage.copy(storedBytes = 249 * megabyte)
        assertFalse(
            "expected the 250 MiB budget to refuse an item that does not fit",
            AutomaticDownloadPolicy.canQueue(nearlyFull, capBytes = 250 * megabyte),
        )
    }

    @Test
    fun `a larger budget accepts what a smaller one refuses`() {
        val usage = AutomaticUsage(storedBytes = 300 * megabyte, trackedCount = 1)

        assertFalse(AutomaticDownloadPolicy.canQueue(usage, capBytes = 250 * megabyte))
        assertTrue(AutomaticDownloadPolicy.canQueue(usage, capBytes = 1024 * megabyte))
    }

    @Test
    fun `the default budget is the documented one`() {
        val usage = AutomaticUsage(storedBytes = AutomaticDownloadPolicy.CAP_BYTES, trackedCount = 1)

        assertFalse(AutomaticDownloadPolicy.canQueue(usage, capBytes = AutomaticDownloadPolicy.CAP_BYTES))
    }

    @Test
    fun `the incoming size is still counted against the budget`() {
        val usage = AutomaticUsage(storedBytes = 100 * megabyte, trackedCount = 1)

        assertTrue(AutomaticDownloadPolicy.canQueue(usage, capBytes = 200 * megabyte))
        assertFalse(
            "a large incoming item must not fit in what is left",
            AutomaticDownloadPolicy.canQueue(usage, capBytes = 101 * megabyte, incomingBytes = 4 * megabyte),
        )
    }

    @Test
    fun `reserved space for pending work counts against the budget`() {
        val usage = AutomaticUsage(
            storedBytes = 100 * megabyte,
            pendingCount = 3,
            trackedCount = 3,
        )

        assertTrue(AutomaticDownloadPolicy.canQueue(usage, capBytes = 200 * megabyte))
        assertFalse(AutomaticDownloadPolicy.canQueue(usage, capBytes = 110 * megabyte))
    }
}
