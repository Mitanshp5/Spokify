package com.mitansh.sidea.playback

import com.mitansh.sidea.core.Track
import org.junit.Assert.*
import org.junit.Test

class QueueCommandDispatcherTest {
    /** In-memory timeline adapter exercises the same dispatcher as the ExoPlayer adapter.
     * Transport state is observable but not exposed as a mutation capability. */
    private class Timeline : QueueMutationPort {
        val rows = mutableListOf(QueueOccurrence("playing", context = "Playlist"), QueueOccurrence("context"), QueueOccurrence("radio", QueueOrigin.RADIO))
        val recordings = mutableListOf("playing", "context", "radio")
        var position = 54321L
        var paused = true
        var volume = .4f
        var planned = emptyList<String>()
        override val occurrences get() = rows.toList()
        override val currentIndex get() = rows.indexOfFirst { it.id == "playing" }
        override fun insert(index: Int, track: Track, occurrence: QueueOccurrence) { rows.add(index, occurrence); recordings.add(index, track.id) }
        override fun remove(index: Int) { rows.removeAt(index); recordings.removeAt(index) }
        override fun move(from: Int, to: Int) { rows.add(to, rows.removeAt(from)); recordings.add(to, recordings.removeAt(from)) }
        override fun synchronizeOrder() {
            val order = queueShuffleOrder(rows.size)
            val indices = generateSequence(order.firstIndex) { order.getNextIndex(it) }.takeWhile { it >= 0 }
            planned = indices.map { rows[it].id }.toList()
        }
    }
    private fun enqueue(next: Boolean = false) = mapOf<String, Any?>("op" to if (next) "next" else "append", "id" to "same", "title" to "Song", "artist" to "Artist", "duration" to 1000L, "artwork" to "")

    @Test fun `real command orchestration inserts duplicate occurrences silently ahead of successors`() {
        val timeline = Timeline()
        val dispatch = QueueCommandDispatcher(timeline, QueueOrderPolicy())
        repeat(2) { assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true, enqueue())) }
        val first = timeline.rows[1].id
        val second = timeline.rows[2].id
        assertNotEquals(first, second)
        assertEquals(listOf("playing", "same", "same", "context", "radio"), timeline.recordings)
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true, enqueue(true)))
        assertEquals(first, timeline.rows[2].id)
        assertEquals(second, timeline.rows[3].id)
        assertEquals(54321L, timeline.position); assertTrue(timeline.paused); assertEquals(.4f, timeline.volume)
        assertEquals(timeline.rows.map { it.id }, timeline.planned)
        assertEquals("Playlist", timeline.rows[1].context)
    }

    @Test fun `move and remove validate live exact occurrences before mutating anything`() {
        val timeline = Timeline()
        val dispatch = QueueCommandDispatcher(timeline, QueueOrderPolicy())
        dispatch.dispatch(true, enqueue()); dispatch.dispatch(true, enqueue())
        val first = timeline.rows[1].id
        val second = timeline.rows[2].id
        val move = mapOf("op" to "move", "current" to "playing", "id" to second, "target" to first)
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true, move))
        assertEquals(second, timeline.planned[1])
        val remove = mapOf("op" to "remove", "current" to "playing", "ids" to listOf(second))
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true, remove))
        val before = timeline.occurrences
        assertEquals(QueueCommandDispatcher.Result.STALE, dispatch.dispatch(true, move))
        assertEquals(QueueCommandDispatcher.Result.STALE, dispatch.dispatch(true, remove))
        assertEquals(QueueCommandDispatcher.Result.STALE, dispatch.dispatch(true, remove + ("ids" to listOf(first, "playing"))))
        assertEquals(QueueCommandDispatcher.Result.STALE, dispatch.dispatch(true, move + ("id" to first) + ("target" to "radio")))
        assertEquals(before, timeline.occurrences)
        assertEquals(54321L, timeline.position); assertTrue(timeline.paused)
    }

    @Test fun `untrusted malformed requests have no mutations and empty queue accepts silently`() {
        val timeline = Timeline()
        val dispatch = QueueCommandDispatcher(timeline, QueueOrderPolicy())
        val before = timeline.occurrences
        assertEquals(QueueCommandDispatcher.Result.PERMISSION_DENIED, dispatch.dispatch(false, enqueue()))
        assertEquals(QueueCommandDispatcher.Result.BAD_VALUE, dispatch.dispatch(true, enqueue() + ("duration" to "1000")))
        assertEquals(before, timeline.occurrences)
        timeline.rows.clear(); timeline.recordings.clear()
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true, enqueue()))
        assertEquals(listOf("same"), timeline.recordings)
        assertTrue(timeline.paused)
    }

    @Test fun `manual drag must not adopt shuffled context order as canonical`() {
        val timeline = Timeline()
        val policy = QueueOrderPolicy()
        timeline.rows.add(2, QueueOccurrence("context2")); timeline.recordings.add(2, "context2")
        policy.planned(timeline.rows, 0, true, kotlin.random.Random(1))
        timeline.move(2, 1) // A shuffled pending context plan.
        val dispatch = QueueCommandDispatcher(timeline, policy)
        dispatch.dispatch(true, enqueue()); dispatch.dispatch(true, enqueue())
        val first = timeline.rows[1].id
        val second = timeline.rows[2].id
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true,
            mapOf("op" to "move", "current" to "playing", "id" to second, "target" to first)))
        assertEquals(listOf("playing", second, first, "context", "context2", "radio"),
            policy.planned(timeline.rows, 0, false))
    }

    @Test fun `normal manual Clear composes exact identities through production dispatcher`() {
        val timeline = Timeline()
        val dispatch = QueueCommandDispatcher(timeline, QueueOrderPolicy())
        repeat(2) { dispatch.dispatch(true, enqueue()) }
        val snapshot = com.mitansh.sidea.ui.QueueSnapshot(timeline.rows.mapIndexed { index, occurrence ->
            com.mitansh.sidea.ui.QueueEntry(index, "Repeated song", "Artist", instanceId = occurrence.id, origin = occurrence.origin)
        }, 0, true)
        val commands = com.mitansh.sidea.ui.queueCommands(snapshot, snapshot, com.mitansh.sidea.ui.QueueAction.ClearManual)
        val ids = commands.map { snapshot.order[(it as com.mitansh.sidea.ui.QueueCommand.Remove).index].instanceId as String }
        timeline.rows += QueueOccurrence("radio2", QueueOrigin.RADIO); timeline.recordings += "radio2"
        assertEquals(QueueCommandDispatcher.Result.SUCCESS, dispatch.dispatch(true,
            mapOf("op" to "remove", "current" to "playing", "ids" to ids)))
        assertEquals(listOf("playing", "context", "radio", "radio2"), timeline.recordings)
        assertEquals(54321L, timeline.position); assertTrue(timeline.paused); assertEquals(.4f, timeline.volume)
        assertEquals(QueueCommandDispatcher.Result.STALE, dispatch.dispatch(true,
            mapOf("op" to "remove", "current" to "playing", "ids" to ids)))
    }

    @Test fun `radio completion between requests stays behind manual FIFO`() {
        val timeline = Timeline()
        val dispatch = QueueCommandDispatcher(timeline, QueueOrderPolicy())
        dispatch.dispatch(true, enqueue())
        val first = timeline.rows[1].id
        timeline.rows += QueueOccurrence("radio2", QueueOrigin.RADIO)
        timeline.recordings += "radio2"
        dispatch.dispatch(true, enqueue())
        assertEquals(first, timeline.planned[1])
        assertEquals(listOf("playing", "same", "same", "context", "radio", "radio2"), timeline.recordings)
    }
}
