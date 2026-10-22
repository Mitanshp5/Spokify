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

class SpotifyImportLiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 240000) fun suppliedPublicPlaylistImportsThroughProductionReadersAndSearch() = runBlocking {
        assumeTrue("Set SIDEA_SPOTIFY_IMPORT_LIVE=1 for a bounded public-network check", System.getenv("SIDEA_SPOTIFY_IMPORT_LIVE") == "1")
        YouTubeCatalog.initialize()
        val catalog = YouTubeCatalog()
        val playlists = PlaylistRepository(MemoryPlaylistDao())
        val sessions = ImportSessionStore(folder.newFolder())
        val provenance = MemoryImportProvenanceDao()
        val coordinator = ImportCoordinator(playlists, provenance, sessions, search = { catalog.search(it) })
        val result = coordinator.read("https://open.spotify.com/playlist/37i9dQZF1EptFBKtaK7Wjn?si=8f9596f275c8480a") {
            println("SPOTIFY_IMPORT_PROGRESS $it")
        }
        assertNull(result.error, result.error)
        val session = result.session
        assertEquals("37i9dQZF1EptFBKtaK7Wjn", session.sourceId)
        assertEquals("On Repeat", session.title)
        assertTrue(session.items.isNotEmpty())
        assertEquals(session.items.indices.toList(), session.items.map { it.position })
        assertEquals(session, sessions.load(session.id))
        assertTrue("Some songs should match automatically", session.items.any { it.selected != null })
        val saved = coordinator.commit(session.id)
        assertNull(saved.error, saved.error)
        assertEquals(session.items.count { it.selected != null }, saved.saved)
        assertEquals(saved.saved, playlists.observeEntries(saved.playlistId!!).first().size)
        assertEquals(saved.saved, provenance.countForPlaylist(saved.playlistId))
        assertTrue(coordinator.commit(session.id).alreadyCommitted)
        println("SPOTIFY_IMPORT_LIVE rows=${session.items.size} saved=${saved.saved} review=${session.items.count { it.status == ImportedItem.Status.REVIEW }} missing=${session.items.count { it.status == ImportedItem.Status.MISSING }} warning=${session.warning}")
    }
}
