package com.mitansh.sidea.data.imports

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Checks that an import session survives a restart and keeps its source identity.
 *
 * The session is stored as a file, so a half-finished import can be resumed and a saved playlist
 * can be traced back to where it came from.
 */
class ImportSessionStoreTest {

    private fun store(folder: File) = ImportSessionStore(folder)

    private fun session(items: List<ImportedItem>) = ImportSessionStore.Session(
        id = "session-1",
        source = ImportedPlaylist.Source.SPOTIFY,
        sourceId = "spotify-id",
        title = "My playlist",
        author = "Someone",
        artworkUrl = null,
        declaredCount = 10,
        complete = false,
        items = items,
        updatedAtEpochMs = 1_000L,
    )

    private fun item(position: Int, title: String) = ImportedItem(
        position = position,
        sourceTrackId = "source-$position",
        title = title,
        artist = "Artist $position",
        durationMs = 200_000L,
        selected = Track("video$position", title, "Artist $position", 200_000L),
        alternatives = listOf(Track("alt$position", "$title (Live)", "Artist $position", 210_000L)),
        status = ImportedItem.Status.MATCHED,
    )

    @Test
    fun `a session survives a reload with its source identity`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)

        store.save(session(listOf(item(0, "First"), item(1, "Second"))))
        val reloaded = store.load("session-1")

        assertNotNull("the session should be readable after a restart", reloaded)
        assertEquals("My playlist", reloaded!!.title)
        assertEquals(ImportedPlaylist.Source.SPOTIFY, reloaded.source)
        assertEquals("spotify-id", reloaded.sourceId)
        assertEquals(2, reloaded.items.size)
        assertEquals("source-0", reloaded.items[0].sourceTrackId)
        assertEquals("First", reloaded.items[0].selected?.title)
        assertEquals(1, reloaded.items[0].alternatives.size)
    }

    @Test
    fun `a session that was not committed is pending`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)

        store.save(session(listOf(item(0, "First"))))

        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a committed session is no longer pending`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)

        store.save(session(listOf(item(0, "First"))).copy(committedPlaylistId = "playlist-1"))

        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a missing session reads as null rather than failing`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)

        assertNull(store.load("nothing-here"))
    }

    @Test
    fun `an unreadable session is ignored rather than crashing the list`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)
        File(folder, "broken.import.json").writeText("{not json")

        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `the number of stored sessions is bounded`() {
        val folder = Files.createTempDirectory("import-session").toFile()
        val store = store(folder)

        repeat(40) { index ->
            store.save(session(listOf(item(0, "Song $index"))).copy(id = "session-$index"))
        }

        val files = folder.listFiles()?.count { it.isFile } ?: 0
        assertTrue("expected the stored sessions to be bounded, got $files", files <= 20)
    }
}
