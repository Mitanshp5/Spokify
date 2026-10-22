package com.mitansh.sidea.playback

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueCoordinatorTest {
    @Test
    fun `radio requests keep the original seed and ignore stale completions`() {
        val queue = PlaybackQueueCoordinator()
        queue.begin(track("seed"))
        val first = queue.request()!!
        queue.begin(track("replacement"))
        assertFalse(queue.completeAndAppend(first, listOf(track("stale")), emptyList(), true) {})
        val current = queue.request()!!
        assertEquals("replacement", current.seed.id)
        assertTrue(queue.completeAndAppend(current, listOf(track("radio")), listOf(track("replacement")), true) {})
    }

    @Test
    fun `radio failure can retry while empty successful page exhausts`() {
        val queue = PlaybackQueueCoordinator()
        queue.begin(track("seed"))
        val first = queue.request()!!
        queue.completeAndAppend(first, listOf(track("radio")), listOf(track("seed")), true, "cursor") {}
        val failed = queue.request()!!
        queue.failed(failed)
        assertTrue(queue.canRequest)
        val empty = queue.request()!!
        assertEquals(failed.continuation, empty.continuation)
        queue.completeAndAppend(empty, emptyList(), listOf(track("seed")), true) {}
        assertFalse(queue.canRequest)
    }

    @Test
    fun `completion appends only unseen tracks when its session is current and repeat is off`() {
        val queue = PlaybackQueueCoordinator()
        queue.begin(listOf(track("seed"), track("user")), 1)
        val request = queue.request()!!
        var appended = emptyList<Track>()
        val accepted = queue.completeAndAppend(request, listOf(track("user"), track("radio")),
            timeline = listOf(track("seed"), track("user")), repeatOff = true,
            continuation = "next") { appended = it }
        assertTrue(accepted)
        assertEquals("user", request.seed.id)
        assertEquals(listOf("radio"), appended.map { it.id })
        val following = queue.request()!!
        assertEquals("user", following.seed.id)
        assertEquals("next", following.continuation)
    }

    @Test
    fun `stale completion and repeat mode do not append`() {
        val queue = PlaybackQueueCoordinator()
        queue.begin(track("old"))
        val old = queue.request()!!
        queue.begin(track("new"))
        var calls = 0
        assertFalse(queue.completeAndAppend(old, listOf(track("stale")), listOf(track("new")), true) { calls++ })
        val current = queue.request()!!
        assertFalse(queue.completeAndAppend(current, listOf(track("radio")), listOf(track("new")), false) { calls++ })
        assertEquals(0, calls)
        assertEquals("new", queue.request()?.seed?.id)
    }

    @Test
    fun `registering appended metadata retains earlier queue items`() {
        val metadata = TrackMetadataRegistry()
        metadata.put(track("first"))
        metadata.put(track("appended"))
        assertEquals("first", metadata.get("first")?.id)
        assertEquals("appended", metadata.get("appended")?.id)
    }

    private fun track(id: String) = Track(id, id, "artist")
}
