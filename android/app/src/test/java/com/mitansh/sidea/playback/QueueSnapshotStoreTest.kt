package com.mitansh.sidea.playback

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Checks that the queue survives a restart.
 *
 * Without this the app comes back to an empty queue, and the user has to start the session
 * again. The store keeps the queue, the position and the seed the radio is building on.
 */
class QueueSnapshotStoreTest {

    private fun store(folder: File) = QueueSnapshotStore(File(folder, "queue-snapshot.json"))

    private fun track(id: String, title: String = "Song $id") = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        durationMs = 200_000L,
        artworkUrl = "https://example.test/$id.jpg",
        source = "youtube",
    )

    private fun snapshot(items: List<Track>, current: Int, position: Long, seed: Track?) =
        QueueSnapshotStore.Snapshot(items, current, position, seed)

    @Test
    fun `a queue survives a reload`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(listOf(track("a"), track("b"), track("c")), 1, 45_000L, track("a")))
        val reloaded = store.load()

        assertNotNull("the queue should be readable after a restart", reloaded)
        assertEquals(3, reloaded!!.items.size)
        assertEquals("Song b", reloaded.items[1].title)
        assertEquals(1, reloaded.currentIndex)
        assertEquals(45_000L, reloaded.positionMs)
    }

    @Test
    fun `the seed the radio is building on is kept`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(listOf(track("a")), 0, 0L, track("seed-track")))
        val reloaded = store.load()

        assertEquals("seed-track", reloaded?.seed?.id)
    }

    @Test
    fun `a queue without a seed reads as no seed`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(listOf(track("a")), 0, 0L, null))

        assertNull(store.load()?.seed)
    }

    @Test
    fun `nothing saved reads as nothing`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()

        assertNull(store(folder).load())
    }

    @Test
    fun `an empty queue is not restored`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(emptyList(), 0, 0L, null))

        assertNull("an empty queue should not be restored", store.load())
    }

    @Test
    fun `a broken file reads as nothing rather than crashing`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)
        File(folder, "queue-snapshot.json").writeText("{not json")

        assertNull(store.load())
    }

    @Test
    fun `the position outside the queue is brought back inside it`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(listOf(track("a"), track("b")), 9, 0L, null))
        val reloaded = store.load()

        assertEquals(1, reloaded?.currentIndex)
    }

    @Test
    fun `clearing removes the saved queue`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)
        store.save(snapshot(listOf(track("a")), 0, 0L, null))

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `the artwork of a track is kept`() {
        val folder = Files.createTempDirectory("queue-snapshot").toFile()
        val store = store(folder)

        store.save(snapshot(listOf(track("a")), 0, 0L, null))

        assertEquals("https://example.test/a.jpg", store.load()?.items?.first()?.artworkUrl)
    }
}
