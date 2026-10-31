package com.mitansh.sidea.playback

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QueueOrderPolicyTest {
    @Test fun `shuffle off restores pending source subsequence behind manual FIFO without resurrecting history`() {
        val source = listOf(occurrence("consumed-manual", QueueOrigin.MANUAL), occurrence("playing"),
            occurrence("m1", QueueOrigin.MANUAL), occurrence("m2", QueueOrigin.MANUAL),
            occurrence("c1"), occurrence("c2"), occurrence("c3"), occurrence("c4"),
            occurrence("r1", QueueOrigin.RADIO), occurrence("r2", QueueOrigin.RADIO))
        val shuffled = policy.planned(source, 1, true, Random(13)).map { id -> source.first { it.id == id } }
        assertNotEquals(source.map { it.id }, shuffled.map { it.id })
        assertEquals(source.map { it.id }, policy.planned(shuffled, 1, false))
        // Playback has advanced through both manual entries and one shuffled context entry.
        val consumedPrefix = shuffled.take(5).map { it.id }
        val expectedPending = source.filter { it.id !in consumedPrefix }.map { it.id }
        assertEquals(consumedPrefix + expectedPending, policy.planned(shuffled, 4, false))
        val order = queueShuffleOrder(source.size)
        val restored = policy.planned(shuffled, 4, false)
        assertEquals(expectedPending, generateSequence(order.getNextIndex(4)) { order.getNextIndex(it) }
            .takeWhile { it >= 0 }.map { restored[it] }.toList())
    }
    @Test fun `installed Media3 shuffle traversal and radio insertion execute the physical plan`() {
        val entries = listOf(occurrence("history", QueueOrigin.MANUAL), occurrence("playing"),
            occurrence("manual", QueueOrigin.MANUAL), occurrence("c1"), occurrence("c2"), occurrence("c3"))
        val physical = policy.shuffled(entries, 1, Random(19))
        val order = queueShuffleOrder(physical.size).cloneAndInsert(physical.size, 2)
        val indices = generateSequence(order.firstIndex) { order.getNextIndex(it) }.takeWhile { it >= 0 }.toList()
        assertEquals((0..7).toList(), indices)
        assertEquals(listOf("history", "playing", "manual"), physical.take(3))
        // Shuffle is a real permutation of context; a toggle never includes history in the pending set.
        assertEquals(setOf("c1", "c2", "c3"), physical.drop(3).toSet())
        assertTrue((0..20).any { policy.shuffled(entries, 1, Random(it)) != entries.map { e -> e.id } })
        val moved = physical.toMutableList().apply { add(3, removeAt(5)) }
        val afterDrag = queueShuffleOrder(moved.size)
        assertEquals(moved.drop(2), generateSequence(afterDrag.getNextIndex(1)) { afterDrag.getNextIndex(it) }
            .takeWhile { it >= 0 }.map { moved[it] }.toList())
    }
    @Test fun `shuffle off excludes removed successors and appends newly fetched radio in source order`() {
        val source = listOf(occurrence("playing"), occurrence("m", QueueOrigin.MANUAL),
            occurrence("a"), occurrence("b"), occurrence("c"), occurrence("r1", QueueOrigin.RADIO))
        val shuffled = policy.planned(source, 0, true, Random(3)).map { id -> source.first { it.id == id } }
        val live = shuffled.filter { it.id != "b" } + occurrence("r2", QueueOrigin.RADIO)
        repeat(5) { assertEquals(listOf("playing", "m", "a", "c", "r1", "r2"), policy.planned(live, 0, false)) }
    }

    @Test fun `explicit shuffled source drag intentionally adopts pending displayed order only`() {
        val source = listOf(occurrence("history"), occurrence("playing"), occurrence("m", QueueOrigin.MANUAL),
            occurrence("a"), occurrence("b"), occurrence("c"))
        policy.planned(source, 1, true, Random(4))
        val dragged = listOf(source[0], source[1], source[2], source[5], source[3], source[4])
        policy.reordered(dragged, 1, QueueOrigin.CONTEXT)
        assertEquals(dragged.map { it.id }, policy.planned(dragged, 1, false))
        assertEquals(listOf("history", "playing", "m", "c", "a", "b"), policy.planned(dragged, 3, false))
    }

    private val policy = QueueOrderPolicy()
    private fun occurrence(id: String, origin: QueueOrigin = QueueOrigin.CONTEXT) = QueueOccurrence(id, origin)

    @Test fun `manual append stays FIFO ahead of context and radio and play next wins`() {
        val queue = mutableListOf(occurrence("playing"), occurrence("context"), occurrence("radio", QueueOrigin.RADIO))
        queue.add(policy.insertion(queue, 0, false), occurrence("first", QueueOrigin.MANUAL))
        queue.add(policy.insertion(queue, 0, false), occurrence("second", QueueOrigin.MANUAL))
        queue.add(policy.insertion(queue, 0, true), occurrence("next", QueueOrigin.MANUAL))
        assertEquals(listOf("playing", "next", "first", "second", "context", "radio"), queue.map { it.id })
    }

    @Test fun `shuffle keeps pending manual order and never resurrects consumed entries`() {
        val entries = listOf(occurrence("consumed", QueueOrigin.MANUAL), occurrence("playing"),
            occurrence("a", QueueOrigin.MANUAL), occurrence("b", QueueOrigin.MANUAL),
            occurrence("c"), occurrence("d"), occurrence("radio", QueueOrigin.RADIO))
        repeat(50) { seed ->
            val order = policy.shuffled(entries, 1, Random(seed))
            assertEquals(listOf("consumed", "playing", "a", "b"), order.take(4))
            assertEquals("radio", order.last())
            assertEquals(entries.size, order.toSet().size)
            val reordered = order.map { id -> entries.first { it.id == id } }
            assertEquals(listOf("consumed", "playing", "a", "b"), policy.shuffled(reordered, 2, Random(seed)).take(4))
        }
    }

    @Test fun `same recording occurrences remain distinct and targets bind to exact identity`() {
        val a = QueueOccurrence(origin = QueueOrigin.MANUAL)
        val b = QueueOccurrence(origin = QueueOrigin.MANUAL)
        assertNotEquals(a.id, b.id)
        val entries = listOf(occurrence("current"), a, b)
        assertEquals(listOf(2), policy.targets(entries, 0, "current", listOf(b.id)))
        assertNull(policy.targets(entries.dropLast(1), 0, "current", listOf(b.id)))
        assertNull(policy.targets(entries, 1, "current", listOf(b.id)))
        assertNull(policy.targets(entries, 0, "current", listOf("current")))
        assertNull(policy.targets(entries, 0, "current", listOf(a.id, a.id)))
        assertEquals(2 to 1, policy.move(entries, 0, "current", b.id, a.id))
        assertNull(policy.move(entries + occurrence("radio", QueueOrigin.RADIO), 0, "current", b.id, "radio"))
    }

    @Test fun `empty queue accepts insertion at zero`() {
        assertEquals(0, policy.insertion(emptyList(), -1, false))
        assertEquals(0, policy.insertion(emptyList(), -1, true))
    }

    @Test fun `live radio append cannot get in front of pending manual`() {
        val queue = mutableListOf(occurrence("playing"), occurrence("manual", QueueOrigin.MANUAL), occurrence("context"))
        queue += occurrence("radio", QueueOrigin.RADIO)
        queue.add(policy.insertion(queue, 0, false), occurrence("manual2", QueueOrigin.MANUAL))
        assertEquals(listOf("playing", "manual", "manual2", "context", "radio"), policy.shuffled(queue, 0, Random(7)))
    }

    private fun enqueue() = mapOf<String, Any?>("op" to "append", "id" to "recording", "title" to "Song", "artist" to "Artist", "duration" to 1000L, "artwork" to "")

    @Test fun `recording without credited artist remains queueable`() {
        assertTrue(QueueRequest.parse(true, enqueue() + ("artist" to "")) is QueueRequest.Request.Enqueue)
    }

    @Test fun `trusted typed command accepts enqueue but rejects unknown malformed and untrusted requests`() {
        assertTrue(QueueRequest.parse(true, enqueue()) is QueueRequest.Request.Enqueue)
        assertNull(QueueRequest.parse(false, enqueue()))
        assertNull(QueueRequest.parse(true, enqueue() + ("duration" to 1000)))
        assertNull(QueueRequest.parse(true, enqueue() + ("duration" to -1L)))
        assertNull(QueueRequest.parse(true, enqueue() + ("surprise" to true)))
        assertNull(QueueRequest.parse(true, enqueue() + ("op" to "replace")))
        assertNull(QueueRequest.parse(true, mapOf("op" to "remove", "current" to "a", "ids" to listOf(1))))
        assertNull(QueueRequest.parse(true, mapOf("op" to "remove", "current" to "a", "ids" to listOf("b", "b"))))
        assertTrue(QueueRequest.parse(true, mapOf("op" to "move", "current" to "a", "id" to "b", "target" to "c")) is QueueRequest.Request.Move)
    }
}
