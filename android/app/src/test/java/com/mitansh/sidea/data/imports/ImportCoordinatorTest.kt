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

    @Test fun sourceArtworkSurvivesReadAndSessionReload() = runBlocking {
        val (coordinator, sessions) = fixture(rows = """{"title":"Song","albumArt":{"sources":[{"url":"https://i.scdn.co/image/source"}]}}""")
        val session = coordinator.read(link).session
        fun artwork(item: ImportedItem): String? = item.sourceArtworkUrl
        assertEquals("https://i.scdn.co/image/source", artwork(session.items.single()))
        assertEquals("https://i.scdn.co/image/source", artwork(sessions.load(session.id)!!.items.single()))
    }

    @Test fun manualChoiceIsConfirmedOnlyAfterDurableWriteAndResumesWithoutSearch() = runBlocking {
        val (coordinator, sessions) = fixture()
        val original = coordinator.read(link).session
        val choice = coordinator.updateSelection(original.id, 2, b)
        assertNull(choice.error)
        assertNotNull(choice.session)
        val loaded = sessions.load(original.id)!!
        assertEquals(b, loaded.items[2].selected)
        assertEquals(ImportedItem.Status.MATCHED, loaded.items[2].status)
        assertNull(loaded.items[0].selected)
        assertEquals(original.copy(items = loaded.items, updatedAtEpochMs = loaded.updatedAtEpochMs), loaded)
        val restarted = ImportCoordinator(playlists, provenance, sessions, search = { error("Resume must not search") })
        assertEquals(loaded, restarted.pendingSessions().single())
        val saved = restarted.commit(loaded.id)
        assertNull(saved.error)
        assertEquals(1, saved.saved)
        assertEquals(listOf(2), provenance.forPlaylist(saved.playlistId!!).map { it.sourcePosition })
    }

    @Test fun rapidChoicesUseStoredCandidatesAndBothReachAnIdempotentSave() = runBlocking {
        val (coordinator, sessions) = fixture()
        val original = coordinator.read(link).session
        val choices = listOf(0, 2).map { position ->
            async { coordinator.updateSelection(original.id, position, b.copy(title = "Untrusted payload")) }
        }.awaitAll()
        assertTrue(choices.all { it.error == null })
        assertEquals(listOf(b, b), sessions.load(original.id)!!.items.mapNotNull { it.selected })
        val saves = List(2) { async { coordinator.commit(original.id) } }.awaitAll()
        assertTrue(saves.all { it.saved == 2 && it.unmatched == 1 })
        assertEquals(1, saves.count { it.alreadyCommitted })
        assertEquals(listOf(b, b), playlists.observeEntries(saves.first().playlistId!!).first().map { it.track })
    }

    @Test fun invalidSourcePositionsAndUnlistedRecordingIdentitiesNeverChangeDraft() = runBlocking {
        val (coordinator, sessions) = fixture()
        val original = coordinator.read(link).session
        for ((position, track) in listOf(-1 to b, 99 to b, 1 to b, 0 to b.copy(source = "other"), 0 to b.copy(id = ""))) {
            val result = coordinator.updateSelection(original.id, position, track)
            assertNotNull(result.error)
            assertNull(result.session)
            assertEquals(original, sessions.load(original.id))
        }
        assertNotNull(coordinator.updateSelection("../outside", 0, b).error)
    }

    @Test fun failedCommitWriteReturnsErrorAndDoesNotLeaveAnUntrackedPlaylist() = runBlocking {
        val dir = folder.newFolder()
        val sessions = ImportSessionStore(dir)
        val (reader, _) = fixture(search = { listOf(a) })
        val original = reader.read(link).session
        sessions.save(original)
        val coordinator = ImportCoordinator(playlists, provenance, sessions, search = { error("No network") })
        val blocker = java.io.File(dir, original.id + ".import.json.tmp")
        blocker.mkdir()
        java.io.File(blocker, "busy").writeText("busy")
        val choice = coordinator.updateSelection(original.id, 0, a)
        assertNull(choice.session)
        assertNotNull(choice.error)
        assertEquals(original, sessions.load(original.id))
        val result = coordinator.commit(original.id)
        assertNull(result.playlistId)
        assertNotNull(result.error)
        assertEquals(original, sessions.load(original.id))
        assertTrue(playlists.observePlaylists().first().isEmpty())
        blocker.deleteRecursively()
        val retry = coordinator.commit(original.id)
        assertNotNull(retry.playlistId)
        assertEquals(1, playlists.observePlaylists().first().size)
    }

    private class SimulatedProcessDeath : Error("Process stopped before the completed session was saved")

    private fun crashDraft(sessionId: String = "interrupted-import") = ImportSessionStore.Session(
        id = sessionId,
        source = ImportedPlaylist.Source.SPOTIFY,
        sourceId = id,
        title = "Same name",
        author = "Artist",
        artworkUrl = null,
        declaredCount = 109,
        complete = true,
        items = listOf(
            ImportedItem(0, "source-a", "First", "Artist", 180000, selected = a, status = ImportedItem.Status.MATCHED),
            ImportedItem(2, null, "Unavailable", "Artist", 0, status = ImportedItem.Status.MISSING),
            ImportedItem(105, "source-b", "Chosen", "Artist", 180000, selected = a, alternatives = listOf(a, b), status = ImportedItem.Status.REVIEW),
            ImportedItem(108, "source-a", "Repeated", "Artist", 180000, selected = a, status = ImportedItem.Status.MATCHED),
        ),
    )

    @Test fun processDeathAfterPlaylistCreationRetriesIntoTheSameDestination() = runBlocking {
        assertInterruptedCommitRecovers(afterProvenanceWrite = false)
    }

    @Test fun processDeathAfterProvenanceWriteRetriesIntoTheSameDestination() = runBlocking {
        assertInterruptedCommitRecovers(afterProvenanceWrite = true)
    }

    private suspend fun assertInterruptedCommitRecovers(afterProvenanceWrite: Boolean) {
        val dao = MemoryPlaylistDao()
        val repository = PlaylistRepository(dao)
        // Same-name independent playlists, including edits, must never be recovery targets.
        val independent = repository.create("Same name", listOf(a, b, a))
        val other = repository.create("Same name", listOf(b))
        repository.rename(independent, "User renamed")
        repository.moveEntry(independent, repository.observeEntries(independent).first().last().entryId, 0)
        repository.append(independent, b)
        val independentBefore = repository.observeEntries(independent).first()
        val otherBefore = repository.observeEntries(other).first()
        val dir = folder.newFolder()
        val store = ImportSessionStore(dir)
        val draft = crashDraft()
        store.save(draft)
        val durableProvenance = MemoryImportProvenanceDao()
        var interruptedId: String? = null
        val interruptedProvenance = object : ImportProvenanceDao by durableProvenance {
            override suspend fun deleteForPlaylist(playlistId: String) {
                interruptedId = playlistId
                if (!afterProvenanceWrite) throw SimulatedProcessDeath()
                durableProvenance.deleteForPlaylist(playlistId)
            }
            override suspend fun insertAll(entries: List<ImportProvenanceEntry>) {
                durableProvenance.insertAll(entries)
                throw SimulatedProcessDeath()
            }
        }
        val interrupted = ImportCoordinator(repository, interruptedProvenance, store, search = { error("No network") })
        try {
            interrupted.commit(draft.id, mapOf(105 to b))
            fail("Process-death stand-in was swallowed")
        } catch (_: SimulatedProcessDeath) { }
        val target = requireNotNull(interruptedId)
        assertEquals(listOf(a, b, a), repository.observeEntries(target).first().map { it.track })
        assertEquals(if (afterProvenanceWrite) 3 else 0, durableProvenance.countForPlaylist(target))
        assertNull(ImportSessionStore(dir).load(draft.id)!!.committedPlaylistId)

        // New coordinator, repository and file store; only durable DB/file state is retained.
        val restartedStore = ImportSessionStore(dir)
        val restartedRepository = PlaylistRepository(dao)
        val restarted = ImportCoordinator(restartedRepository, durableProvenance, restartedStore, search = { error("No network") })
        assertEquals(draft.id, restarted.pendingSessions().single().id)
        val recovered = restarted.commit(draft.id)
        assertNull(recovered.error)
        assertEquals("Cold retry must not add a second import destination", 3, restartedRepository.observePlaylists().first().size)
        assertEquals(target, recovered.playlistId)
        assertEquals(3, recovered.saved)
        assertEquals(1, recovered.unmatched)
        assertEquals(listOf(a, b, a), restartedRepository.observeEntries(target).first().map { it.track })
        assertEquals(listOf(0, 1, 2), restartedRepository.observeEntries(target).first().map { it.position })
        val rows = durableProvenance.forPlaylist(target)
        assertEquals(listOf(0, 105, 108), rows.map { it.sourcePosition })
        assertEquals(listOf("source-a", "source-b", "source-a"), rows.map { it.sourceTrackId })
        assertEquals(listOf("a", "b", "a"), rows.map { it.chosenTrackId })
        assertTrue(rows.all { it.source == "SPOTIFY" && it.playlistId == target })
        assertEquals(listOf(a, b, a), restartedStore.load(draft.id)!!.items.mapNotNull { it.selected })
        assertEquals(target, restartedStore.load(draft.id)!!.committedPlaylistId)
        assertTrue(restarted.pendingSessions().isEmpty())
        val again = restarted.commit(draft.id)
        assertTrue(again.alreadyCommitted)
        assertEquals(target, again.playlistId)
        assertEquals(3, restartedRepository.observePlaylists().first().size)
        assertEquals(3, durableProvenance.countForPlaylist(target))
        assertEquals(independentBefore, restartedRepository.observeEntries(independent).first())
        assertEquals(otherBefore, restartedRepository.observeEntries(other).first())
        assertEquals("User renamed", restartedRepository.observePlaylists().first().single { it.id == independent }.name)
    }

    @Test fun distinctSessionsWithIdenticalSourcesAndNamesCreateSeparatePlaylists() = runBlocking {
        val store = ImportSessionStore(folder.newFolder())
        val coordinator = ImportCoordinator(playlists, provenance, store, search = { error("No network") })
        val drafts = listOf(crashDraft("first-import"), crashDraft("second-import"))
        val results = drafts.map { draft ->
            store.save(draft)
            coordinator.commit(draft.id, mapOf(105 to b))
        }
        assertTrue(results.all { it.error == null })
        assertEquals(2, results.map { it.playlistId }.toSet().size)
        assertEquals(2, playlists.observePlaylists().first().size)
        results.forEach { result ->
            assertEquals(listOf(a, b, a), playlists.observeEntries(result.playlistId!!).first().map { it.track })
            assertEquals(listOf(0, 105, 108), provenance.forPlaylist(result.playlistId).map { it.sourcePosition })
        }
    }

    @Test fun ordinaryProvenanceFailureRollsBackAndRetrySucceeds() = runBlocking {
        val dir = folder.newFolder()
        val store = ImportSessionStore(dir)
        val draft = crashDraft()
        store.save(draft)
        var failedId: String? = null
        val failing = object : ImportProvenanceDao by provenance {
            override suspend fun insertAll(entries: List<ImportProvenanceEntry>) {
                failedId = entries.first().playlistId
                provenance.insertAll(entries)
                throw IOException("Storage unavailable")
            }
        }
        val coordinator = ImportCoordinator(playlists, failing, store, search = { error("No network") })
        val result = coordinator.commit(draft.id, mapOf(105 to b))
        assertNotNull(result.error)
        assertNull(result.playlistId)
        assertTrue(playlists.observePlaylists().first().isEmpty())
        assertEquals(0, provenance.countForPlaylist(requireNotNull(failedId)))
        assertNull(ImportSessionStore(dir).load(draft.id)!!.committedPlaylistId)
        val retry = ImportCoordinator(playlists, provenance, ImportSessionStore(dir), search = { error("No network") })
            .commit(draft.id, mapOf(105 to b))
        assertNull(retry.error)
        assertEquals(1, playlists.observePlaylists().first().size)
        assertEquals(listOf(a, b, a), playlists.observeEntries(retry.playlistId!!).first().map { it.track })
        assertEquals(3, provenance.countForPlaylist(retry.playlistId))
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
