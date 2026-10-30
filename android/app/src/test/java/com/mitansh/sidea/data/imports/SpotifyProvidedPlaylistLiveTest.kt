package com.mitansh.sidea.data.imports

import com.mitansh.sidea.data.library.MemoryPlaylistDao
import com.mitansh.sidea.data.library.PlaylistRepository
import com.mitansh.sidea.data.youtube.YouTubeCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Opt-in acceptance for the user's >100-song playlist, through the actual import/save path. */
class SpotifyProvidedPlaylistLiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 900000) fun fullPlaylistIsMatchedPersistedAndSavedInOrder() = runBlocking {
        assumeTrue("Set SIDEA_SPOTIFY_SAMPLE_LIVE=1", System.getenv("SIDEA_SPOTIFY_SAMPLE_LIVE") == "1")
        YouTubeCatalog.initialize()
        val catalog = YouTubeCatalog()
        val playlists = PlaylistRepository(MemoryPlaylistDao())
        val sessionFolder = folder.newFolder()
        val sessions = ImportSessionStore(sessionFolder)
        val provenance = MemoryImportProvenanceDao()
        val coordinator = ImportCoordinator(playlists, provenance, sessions, search = { catalog.search(it) })
        val start = System.currentTimeMillis()
        val result = coordinator.read("https://open.spotify.com/playlist/6qFMjTCsg6JuL39PvsSOSY?si=u-THLLOeR9G6i6xUP4k9cQ&utm_source=copy-link") {
            println("SAMPLE_IMPORT $it")
        }
        assertNull(result.error, result.error)
        val session = result.session
        assertEquals("6qFMjTCsg6JuL39PvsSOSY", session.sourceId)
        assertTrue("Must read beyond the old 100-row preview", session.items.size > 100)
        assertTrue("Must fetch all declared rows", session.complete)
        assertEquals(session.declaredCount, session.items.size)
        assertEquals(session.items.indices.toList(), session.items.map { it.position })
        assertEquals(session, sessions.load(session.id))
        val out = File("../.work/evidence").apply { mkdirs() }
        File(out, "spotify-provided-playlist-live-session.json").writeText(File(sessionFolder, session.id + ".import.json").readText())
        val matched = session.items.count { it.selected != null }
        val review = session.items.count { it.status == ImportedItem.Status.REVIEW }
        val missing = session.items.count { it.status == ImportedItem.Status.MISSING }
        println("SAMPLE_IMPORT_RESULT rows=${session.items.size} matched=$matched review=$review missing=$missing elapsedMs=${System.currentTimeMillis() - start} warning=${session.warning}")
        // Baseline comparisons can explicitly set 1; normal acceptance requires a useful match rate.
        val minimum = System.getenv("SIDEA_SPOTIFY_SAMPLE_MIN_MATCHES")?.toInt() ?: 100
        assertTrue("Expected at least $minimum confident matches, got $matched", matched >= minimum)
        val saved = coordinator.commit(session.id)
        assertNull(saved.error, saved.error)
        assertEquals(matched, saved.saved)
        val entries = playlists.observeEntries(saved.playlistId!!).first()
        assertEquals(session.items.mapNotNull { it.selected?.id }, entries.map { it.track.id })
        assertEquals(session.items.filter { it.selected != null }.map { it.position }, provenance.forPlaylist(saved.playlistId).map { it.sourcePosition })
        assertTrue(coordinator.commit(session.id).alreadyCommitted)
    }
}
