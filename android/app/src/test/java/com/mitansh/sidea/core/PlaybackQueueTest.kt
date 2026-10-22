package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    @Test
    fun `keeps the ordered search results and starts at the tapped track`() {
        val queue = PlaybackQueue(results(), startIndex = 1)

        assertEquals(3, queue.size)
        assertEquals(listOf("a", "b", "c"), queue.snapshot().map { it.id })
        assertEquals("b", queue.current?.id)
    }

    @Test
    fun `moveNext advances in order and stops at the last track`() {
        val queue = PlaybackQueue(results(), startIndex = 0)

        assertEquals("b", queue.moveNext()?.id)
        assertEquals("c", queue.moveNext()?.id)
        assertFalse(queue.hasNext())
        assertEquals("c", queue.moveNext()?.id)
    }

    @Test
    fun `movePrevious walks back and stops at the first track`() {
        val queue = PlaybackQueue(results(), startIndex = 2)

        assertEquals("b", queue.movePrevious()?.id)
        assertEquals("a", queue.movePrevious()?.id)
        assertFalse(queue.hasPrevious())
        assertEquals("a", queue.movePrevious()?.id)
    }

    @Test
    fun `jumpTo clamps an out-of-range target instead of failing`() {
        val queue = PlaybackQueue(results(), startIndex = 0)

        assertEquals("c", queue.jumpTo(99)?.id)
        assertEquals("a", queue.jumpTo(-4)?.id)
    }

    @Test
    fun `an empty queue reports no current track and no navigation`() {
        val queue = PlaybackQueue(emptyList(), startIndex = 3)

        assertNull(queue.current)
        assertFalse(queue.hasNext())
        assertFalse(queue.hasPrevious())
        assertNull(queue.moveNext())
        assertNull(queue.movePrevious())
    }

    @Test
    fun `a single-track queue starts on that track with no navigation`() {
        val queue = PlaybackQueue(listOf(track("a")))

        assertEquals("a", queue.current?.id)
        assertTrue(queue.currentIndex == 0)
        assertFalse(queue.hasNext())
        assertFalse(queue.hasPrevious())
    }

    private fun results() = listOf(track("a"), track("b"), track("c"))

    private fun track(id: String) = Track(id = id, title = id, artist = "Artist")
}
