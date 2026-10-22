package com.mitansh.sidea.data.imports

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.ImportProvenanceDao
import com.mitansh.sidea.data.library.ImportProvenanceEntry
import com.mitansh.sidea.data.library.MemoryPlaylistDao
import com.mitansh.sidea.data.library.PlaylistRepository
import com.mitansh.sidea.data.spotify.SpotifyApiPage
import com.mitansh.sidea.data.spotify.SpotifyApiTransport
import com.mitansh.sidea.data.spotify.SpotifyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ImportCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "37i9dQZF1EptFBKtaK7Wjn"
    private val link = "https://open.spotify.com/playlist/$id?si=8f9596f275c8480a"
    private val a = Track("a", "Song", "Artist", 180000)
    private val b = a.copy(id = "b")
    private val playlists = PlaylistRepository(MemoryPlaylistDao())
    private val provenance = MemoryImportProvenanceDao()

    private fun fixture(
        rows: String = """{"title":"Song","subtitle":"Artist","duration":180000,"uri":"spotify:track:0123456789ABCDEFGHIJKL"},{"title":"","subtitle":""},{"title":"Song","subtitle":"Artist","duration":180000,"uri":"spotify:track:0123456789ABCDEFGHIJKL"}""",
        search: suspend (String) -> List<Track> = { listOf(a, b) },
    ): Pair<ImportCoordinator, ImportSessionStore> {
        val sessions = ImportSessionStore(folder.newFolder())
        val spotify = SpotifyRepository(SpotifyApiTransport { url ->
            assertEquals("https://open.spotify.com/embed/playlist/$id", url)
            SpotifyApiPage("""<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"On Repeat","trackList":[$rows]}}}}}}</script>""")
        })
        return ImportCoordinator(playlists, provenance, sessions, spotify, search = search) to sessions
    }

    @Test fun actualLinkReadsMatchesPersistsAndSavesReviewedChoicesInSourceOrder() = runBlocking {
        val (coordinator, sessions) = fixture()
        val progress = mutableListOf<String>()
        val result = coordinator.read(link) { progress += it }
        assertNull(result.error)
        assertEquals(id, result.session.sourceId)
        assertEquals(3, result.session.items.size)
        assertEquals(3, progress.size)
        assertEquals(ImportedItem.Status.REVIEW, result.session.items[0].status)
        assertNull(sessions.load(result.session.id)!!.declaredCount)
        assertEquals(result.session, sessions.load(result.session.id))

        val saved = coordinator.commit(result.session.id, mapOf(0 to b, 2 to b))
        assertNull(saved.error)
        assertEquals(2, saved.saved)
        assertEquals(1, saved.unmatched)
        assertEquals(listOf(b, b), playlists.observeEntries(saved.playlistId!!).first().map { it.track })
        assertEquals(listOf(0, 2), provenance.forPlaylist(saved.playlistId).map { it.sourcePosition })
        assertEquals(listOf("b", "b"), sessions.load(result.session.id)!!.items.mapNotNull { it.selected?.id })
        assertEquals(emptyList<ImportSessionStore.Session>(), coordinator.pendingSessions())
        val again = coordinator.commit(result.session.id)
        assertTrue(again.alreadyCommitted)
        assertEquals(saved.playlistId, again.playlistId)
        assertEquals(2, again.saved)
        assertEquals(1, again.unmatched)
        assertEquals(1, playlists.observePlaylists().first().size)
    }

    @Test fun concurrentSavesOfOneSessionCreateOnePlaylist() = runBlocking {
        val (coordinator, _) = fixture(search = { listOf(a) })
        val session = coordinator.read(link).session
        val saves = List(2) { async { coordinator.commit(session.id) } }.awaitAll()
        assertEquals(1, saves.map { it.playlistId }.toSet().size)
        assertEquals(1, saves.count { it.alreadyCommitted })
        assertEquals(1, playlists.observePlaylists().first().size)
    }

    @Test fun emptySelectionAndUnknownCandidateDoNotCreatePlaylists() = runBlocking {
        val (coordinator, _) = fixture()
        val session = coordinator.read(link).session
        assertNotNull(coordinator.commit(session.id).error)
        assertNotNull(coordinator.commit(session.id, mapOf(0 to a.copy(id = "unknown"))).error)
        assertNotNull(coordinator.commit(session.id, mapOf(99 to a)).error)
        assertTrue(playlists.observePlaylists().first().isEmpty())
    }

    @Test fun oneFailedSearchKeepsOtherRowsAndPersistsAnHonestWarning() = runBlocking {
        var calls = 0
        val (coordinator, sessions) = fixture(search = {
            if (calls++ == 0) throw IOException("offline")
            listOf(a)
        })
        val read = coordinator.read(link)
        assertNull(read.error)
        assertEquals(3, read.session.items.size)
        assertNull(read.session.items[0].selected)
        assertEquals(a, read.session.items[2].selected)
        assertTrue(read.session.warning!!.contains("1 song searches failed"))
        assertEquals(read.session.warning, sessions.load(read.session.id)!!.warning)
    }

    @Test fun failedSearchesAndEmptyPreviewReturnActionableErrors() = runBlocking {
        val (failed, sessions) = fixture(search = { throw IOException("offline") })
        assertTrue(failed.read(link).error!!.contains("matching is unavailable"))
        assertTrue(sessions.pending().isEmpty())
        val (empty, _) = fixture(rows = "")
        assertTrue(empty.read(link).error!!.contains("No songs"))
    }

    @Test fun canceledSearchPropagatesCancellationWithoutSavingASession() = runBlocking {
        val (coordinator, sessions) = fixture(search = { throw CancellationException("cancel") })
        try {
            coordinator.read(link)
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertTrue(sessions.pending().isEmpty())
    }
}

internal class MemoryImportProvenanceDao : ImportProvenanceDao {
    private val entries = mutableListOf<ImportProvenanceEntry>()
    override suspend fun forPlaylist(playlistId: String) = entries.filter { it.playlistId == playlistId }.sortedBy { it.sourcePosition }
    override suspend fun countForPlaylist(playlistId: String) = entries.count { it.playlistId == playlistId }
    override suspend fun insertAll(entries: List<ImportProvenanceEntry>) { this.entries += entries }
    override suspend fun deleteForPlaylist(playlistId: String) { entries.removeAll { it.playlistId == playlistId } }
}
