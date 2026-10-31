package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class StorageLimitInputTest {
    @Test fun supportedBudgetBoundsAreExact() {
        assertEquals(100L * 1024 * 1024, storageLimitBytes("100"))
        assertEquals(51200L * 1024 * 1024, storageLimitBytes("51200"))
        assertEquals(500L * 1024 * 1024, storageLimitBytes("500"))
    }

    @Test fun valuesTheRepositoryWouldSilentlyClampAreRejected() {
        listOf("0", "1", "99", "51201", "9223372036854775807", "8796093022208")
            .forEach { assertNull("Reject unsupported MiB value: $it", storageLimitBytes(it)) }
    }

    @Test fun malformedAndBlankDraftsCannotSubmit() {
        listOf("", " ", "100 ", "+100", "-100", "100.5", "1e3", "100MB", "９９９")
            .forEach { assertNull("Reject malformed MiB value: $it", storageLimitBytes(it)) }
    }

    @Test fun everySupportedWholeMiBValueIsAcceptedWithoutRounding() {
        (100L..51200L).forEach { assertEquals(it * 1024 * 1024, storageLimitBytes(it.toString())) }
    }
}
