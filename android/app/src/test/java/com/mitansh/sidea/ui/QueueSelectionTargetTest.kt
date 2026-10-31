package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class QueueSelectionTargetTest {
    private val current = QueueEntry(0, "Current", "Artist", instanceId = "current")
    private val first = QueueEntry(1, "Repeated", "Artist", instanceId = "first")
    private val second = QueueEntry(2, "Repeated", "Artist", instanceId = "second")
    private val shown = QueueSnapshot(listOf(current, first, second), 0)

    @Test fun duplicateRecordingSelectionUsesExactOccurrenceAfterIndexChanges() {
        assertEquals(1, queueSelectionTarget(shown, second, "current", listOf("current", "second", "first")))
    }

    @Test fun currentSelectionCanResumeWithoutReplacingOccurrence() {
        assertEquals(0, queueSelectionTarget(shown, current, "current", listOf("current", "first", "second")))
    }

    @Test fun removedTargetChangedCurrentAndUnknownIdentityAreRejected() {
        assertNull(queueSelectionTarget(shown, second, "current", listOf("current", "first")))
        assertNull(queueSelectionTarget(shown, second, "first", listOf("current", "first", "second")))
        assertNull(queueSelectionTarget(shown, second.copy(instanceId = "other"), "current", listOf("current", "other")))
        assertNull(queueSelectionTarget(shown, second, "current", listOf("current", "second", "second")))
    }
}
