package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class QueuePresentationTest {
    @Test fun `normal Clear removes manual pending duplicates only not current history source or radio`() {
        val manual = com.mitansh.sidea.playback.QueueOrigin.MANUAL
        val radio = com.mitansh.sidea.playback.QueueOrigin.RADIO
        val state = QueueSnapshot(listOf(entry(0).copy(origin = manual), entry(1).copy(origin = manual),
            entry(2).copy(origin = manual), entry(3).copy(origin = manual), entry(4), entry(5).copy(origin = radio)), 1, true)
        assertEquals(listOf(QueueCommand.Remove(3), QueueCommand.Remove(2)), queueCommands(state, state, QueueAction.ClearManual))
        assertTrue(queueCommands(state, state.copy(currentIndex = 2), QueueAction.ClearManual).isEmpty())
        val cleared = state.copy(order = state.order.filter { it.index !in listOf(2, 3) })
        assertTrue(queueCommands(cleared, cleared, QueueAction.ClearManual).isEmpty())
    }

    @Test fun `selection drops current removed and stale identities but retains moved occurrences`() {
        val state = QueueSnapshot((0..3).map(::entry), 1)
        assertEquals(setOf("instance-2"), queueSelection(setOf("instance-0", "instance-1", "instance-2", "removed"), state))
        val moved = state.copy(order = listOf(entry(0), entry(1), entry(3), entry(2)))
        assertEquals(setOf("instance-2"), queueSelection(setOf("instance-2"), moved))
        assertTrue(queueSelection(setOf("instance-2"), moved.copy(currentIndex = 2)).isEmpty())
    }
    @Test fun `shuffle successors can be reordered without touching current`() {
        val state = QueueSnapshot((0..3).map(::entry), 0, true)
        assertEquals(listOf(QueueCommand.Move(3, 2)), queueCommands(state, state, QueueAction.Move(entry(3), true)))
    }
    @Test fun `lazy row keys are saveable primitives stable across duplicate row index changes`() {
        val keys = QueueRowKeys()
        val firstUid = Any()
        val secondUid = Any()
        val firstKey = keys.key(firstUid)
        val secondKey = keys.key(secondUid)
        assertNotEquals(firstKey, secondKey)
        assertEquals(secondKey, keys.key(secondUid))
        assertEquals(firstKey, keys.key(firstUid))
    }

    @Test fun `current and upcoming follow playback order not playlist indices`() {
        val order = listOf(entry(3), entry(1), entry(0), entry(2))
        val result = QueuePresentation.project(order, 1)
        assertEquals(entry(1), result.current)
        assertEquals(listOf(0, 2), result.upcoming.map { it.index })
    }

    @Test fun `empty absent and last current have no upcoming`() {
        assertNull(QueuePresentation.project(emptyList(), -1).current)
        assertTrue(QueuePresentation.project(listOf(entry(0)), -1).upcoming.isEmpty())
        assertTrue(QueuePresentation.project(listOf(entry(0)), 0).upcoming.isEmpty())
    }

    @Test fun `timeline traversal preserves shuffled indices and stops without repeating`() {
        val next = mapOf(3 to 1, 1 to 0, 0 to 2, 2 to -1)
        assertEquals(listOf(3, 1, 0, 2), queuePlaybackIndices(4, 3) { next.getValue(it) })
        assertEquals(listOf(1, 2), queuePlaybackIndices(3, 1) { if (it == 1) 2 else 1 })
        assertTrue(queuePlaybackIndices(0, -1) { error("Empty timeline must not be read") }.isEmpty())
    }

    @Test fun `remove and move target duplicate instances by concrete playlist index`() {
        val state = QueueSnapshot((0..3).map(::entry), 0)
        assertEquals(listOf(QueueCommand.Remove(2)), queueCommands(state, state, QueueAction.Remove(entry(2))))
        assertEquals(listOf(QueueCommand.Move(2, 1)), queueCommands(state, state, QueueAction.Move(entry(2), true)))
        assertEquals(listOf(QueueCommand.Move(1, 2)), queueCommands(state, state, QueueAction.Move(entry(1), false)))
    }

    @Test fun `clear removes only upcoming in descending concrete indices even when shuffled`() {
        val state = QueueSnapshot(listOf(entry(3), entry(1), entry(0), entry(2)), 1, true)
        val commands = queueCommands(state, state, QueueAction.ClearUpcoming)
        assertEquals(listOf(QueueCommand.Remove(2), QueueCommand.Remove(0)), commands)
        val remaining = (0..3).toMutableList()
        commands.forEach { remaining.removeAt((it as QueueCommand.Remove).index) }
        assertEquals(listOf(1, 3), remaining)
    }

    @Test fun `current history boundaries shuffle and stale snapshots reject invalid mutations`() {
        val state = QueueSnapshot((0..3).map(::entry), 1)
        for (index in 0..1) {
            assertTrue(queueCommands(state, state, QueueAction.Remove(entry(index))).isEmpty())
            assertTrue(queueCommands(state, state, QueueAction.Move(entry(index), false)).isEmpty())
        }
        assertTrue(queueCommands(state, state, QueueAction.Move(entry(2), true)).isEmpty())
        assertTrue(queueCommands(state, state, QueueAction.Move(entry(3), false)).isEmpty())
        val shuffled = state.copy(shuffled = true)
        // Shuffle is now executed by the service's explicit plan, so successor moves are safe.
        assertEquals(listOf(QueueCommand.Move(3, 2)), queueCommands(shuffled, shuffled, QueueAction.Move(entry(3), true)))
        val removed = state.copy(order = listOf(entry(0), entry(1), entry(3)))
        assertTrue(queueCommands(state, removed, QueueAction.Remove(entry(2))).isEmpty())
        assertTrue(queueCommands(state, state.copy(currentIndex = 2), QueueAction.ClearUpcoming).isEmpty())
        assertTrue(queueCommands(QueueSnapshot(), QueueSnapshot(), QueueAction.ClearUpcoming).isEmpty())
    }

    @Test fun `select current resumes without seeking while upcoming seeks then plays`() {
        val state = QueueSnapshot((0..2).map(::entry), 1)
        assertEquals(listOf(QueueCommand.Select(1, false)), queueCommands(state, state, QueueAction.Select(entry(1))))
        assertEquals(listOf(QueueCommand.Select(2, true)), queueCommands(state, state, QueueAction.Select(entry(2))))
        assertTrue(queueCommands(state, state, QueueAction.Select(entry(8))).isEmpty())
    }

    @Test fun `duplicate entries retain instance identity after move remove and reindex`() {
        var state = QueueSnapshot((0..3).map(::entry), 0)
        val movedInstance = state.order[3].instanceId
        val move = queueCommands(state, state, QueueAction.Move(state.order[3], true)).single() as QueueCommand.Move
        val moved = state.order.toMutableList().apply { add(move.to, removeAt(move.from)) }
        val oldState = state
        state = state.copy(order = moved.mapIndexed { index, entry -> entry.copy(index = index) })
        assertEquals(movedInstance, state.presentation.upcoming[1].instanceId)
        val target = state.order[2]
        assertEquals(listOf(QueueCommand.Select(2, true)), queueCommands(state, state, QueueAction.Select(target)))
        assertTrue(queueCommands(oldState, state, QueueAction.Remove(oldState.order[2])).isEmpty())
        val remove = queueCommands(state, state, QueueAction.Remove(target)).single() as QueueCommand.Remove
        val remaining = state.order.toMutableList().apply { removeAt(remove.index) }
        state = state.copy(order = remaining.mapIndexed { index, entry -> entry.copy(index = index) })
        assertEquals(listOf("instance-0", "instance-1", "instance-2"), state.order.map { it.instanceId })
        assertEquals(listOf(QueueCommand.Remove(2)), queueCommands(state, state, QueueAction.Remove(state.order[2])))
        assertTrue(queueCommands(state, state, QueueAction.Remove(target)).isEmpty())
    }

    @Test fun `clear unshuffled retains current and all earlier entries`() {
        val state = QueueSnapshot((0..4).map(::entry), 2)
        val remaining = state.order.toMutableList()
        queueCommands(state, state, QueueAction.ClearUpcoming).forEach { remaining.removeAt((it as QueueCommand.Remove).index) }
        assertEquals(listOf(0, 1, 2), remaining.map { it.index })
        assertEquals(entry(2), QueuePresentation.project(remaining, 2).current)
    }

    private fun entry(index: Int) = QueueEntry(index, "Duplicate song", "Same artist", instanceId = "instance-$index")
}
