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
    @Test fun `snapshot writes canonical occurrence order for shuffle off after restart`() {
        val folder = Files.createTempDirectory("queue-canonical").toFile()
        val input = QueueSnapshotStore.Snapshot(listOf(track("same"), track("same")), 0, 1200L, null,
            listOf(QueueOccurrence("a"), QueueOccurrence("b")), true)
        store(folder).save(input)
        val json = com.grack.nanojson.JsonParser.`object`().from(File(folder, "queue-snapshot.json").readText())
        assertEquals(listOf("a", "b"), json.getArray("canonicalOrder")?.toList())
    }

    @Test fun `legacy snapshot loads distinct context occurrences for repeated recordings`() {
        val folder = Files.createTempDirectory("queue-legacy").toFile()
        File(folder, "queue-snapshot.json").writeText("""{"items":[{"id":"same"},{"id":"same"}],"currentIndex":0,"positionMs":1234}""")
        val restored = store(folder).load()!!
        assertEquals(2, restored.occurrences.size)
        assertEquals(listOf(QueueOrigin.CONTEXT, QueueOrigin.CONTEXT), restored.occurrences.map { it.origin })
        assertEquals(2, restored.occurrences.map { it.id }.distinct().size)
        assertEquals(1234L, restored.positionMs)
    }

    @Test fun `occurrence provenance context shuffle and pending order roundtrip`() {
        val folder = Files.createTempDirectory("queue-origins").toFile()
        val input = QueueSnapshotStore.Snapshot(listOf(track("same"), track("same"), track("radio")), 0, 4321L, track("seed"),
            listOf(QueueOccurrence("current", QueueOrigin.CONTEXT, "Playlist"), QueueOccurrence("manual", QueueOrigin.MANUAL, "Playlist"),
                QueueOccurrence("radio", QueueOrigin.RADIO, "Playlist")), true)
        store(folder).save(input)
        assertEquals(input, store(folder).load())
    }

    @Test fun `shuffled restart restores canonical pending source order and rejects malformed saved ordering`() {
        val folder = Files.createTempDirectory("queue-canonical-restore").toFile()
        val entries = listOf(QueueOccurrence("history", QueueOrigin.MANUAL), QueueOccurrence("current"),
            QueueOccurrence("manual", QueueOrigin.MANUAL), QueueOccurrence("c3"), QueueOccurrence("c1"), QueueOccurrence("c2"))
        val canonical = listOf("history", "current", "manual", "c1", "c2", "c3")
        val input = QueueSnapshotStore.Snapshot(entries.map { track("same") }, 1, 12345L, null, entries, true, canonical)
        store(folder).save(input)
        val restored = store(folder).load()!!
        assertEquals(input, restored)
        val policy = QueueOrderPolicy().apply { restore(restored.canonicalOrder) }
        assertEquals(canonical, policy.planned(restored.occurrences, restored.currentIndex, false))
        val file = File(folder, "queue-snapshot.json")
        val json = com.grack.nanojson.JsonParser.`object`().from(file.readText())
        json.put("canonicalOrder", com.grack.nanojson.JsonArray().apply { add("stale"); add("stale") })
        file.writeText(com.grack.nanojson.JsonWriter.string(json))
        assertEquals(entries.map { it.id }, store(folder).load()!!.canonicalOrder)
    }

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
