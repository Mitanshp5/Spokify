package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.*
import com.mitansh.sidea.data.library.*
import com.mitansh.sidea.data.spotify.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportReviewDraftStateTest {
    @get:Rule val folder = TemporaryFolder()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.Before fun mainDispatcher() { kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @org.junit.After fun resetDispatcher() { kotlinx.coroutines.Dispatchers.resetMain() }
    private val a = Track("a", "Song", "Artist", 180000, "https://example.test/recording")
    private val b = a.copy(id = "b")
    private val playlistId = "37i9dQZF1EptFBKtaK7Wjn"
    private val link = "https://open.spotify.com/playlist/$playlistId"
    private val playlists = PlaylistRepository(MemoryPlaylistDao())
    private val provenance = MemoryImportProvenanceDao()

    private fun draft(id: String = "draft") = ImportSessionStore.Session(
        id, ImportedPlaylist.Source.SPOTIFY, playlistId, "Original 133", "Author", "https://example.test/playlist",
        133, true, List(133) { position -> ImportedItem(position, "repeat", "Song", "Artist", 180000,
            selected = a.takeIf { position < 105 }, alternatives = listOf(a, b),
            status = if (position < 105) ImportedItem.Status.MATCHED else ImportedItem.Status.REVIEW,
            sourceArtworkUrl = "https://example.test/source") }, warning = "Retained warning")

    private fun coordinator(sessions: ImportSessionStore) = ImportCoordinator(playlists, provenance, sessions,
        search = { error("Resume must never search") })

    @Test fun productionChoiceCallbackSurvivesLeavingAndRestartAndKeepsAll133SourceRows() = runBlocking {
        val dir = folder.newFolder()
        val sessions = ImportSessionStore(dir)
        val original = draft()
        sessions.save(original)
        val state = ImportReviewDraftState(coordinator(sessions))
        state.refreshPending()
        assertNull(state.session) // Loading drafts must not force navigation/review.
        state.resume(original.id)
        assertEquals(105, state.session!!.items.count { it.selected != null })
        state.choose(120, b)
        assertNull(state.error)
        assertEquals(106, state.session!!.items.count { it.selected != null })
        assertTrue(state.choiceMessage!!.contains("121"))
        state.newLink()
        assertNull(state.session)
        assertEquals(1, state.pending.size)
        val restarted = ImportReviewDraftState(coordinator(ImportSessionStore(dir)))
        restarted.refreshPending()
        restarted.resume(original.id)
        val loaded = restarted.session!!
        assertEquals(b, loaded.items[120].selected)
        assertEquals(original.copy(items = loaded.items, updatedAtEpochMs = loaded.updatedAtEpochMs), loaded)
        assertEquals((0..132).toList(), loaded.items.map { it.position })
    }

    @Test fun productionSaveCallbackReportsDestinationIdAndActualCountsOnlyAfterCommit() = runBlocking {
        val sessions = ImportSessionStore(folder.newFolder())
        sessions.save(draft())
        val state = ImportReviewDraftState(coordinator(sessions))
        state.resume("draft")
        state.choose(120, b)
        var feedback: ImportCoordinator.CommitResult? = null
        state.save { result ->
            feedback = result
            assertEquals(result.playlistId, sessions.load("draft")!!.committedPlaylistId)
        }
        val result = feedback!!
        assertEquals(106, result.saved)
        assertEquals(27, result.unmatched)
        assertEquals(106, playlists.observeEntries(result.playlistId!!).first().size)
        assertEquals((0..104).toList() + 120, provenance.forPlaylist(result.playlistId).map { it.sourcePosition })
        assertNull(state.session)
        assertTrue(state.pending.isEmpty())
    }

    @Test fun rapidProductionChoiceCallbacksAndSaveRetainEveryAcknowledgedPosition() = runBlocking {
        val sessions = ImportSessionStore(folder.newFolder())
        sessions.save(draft())
        val state = ImportReviewDraftState(coordinator(sessions))
        state.resume("draft")
        coroutineScope {
            launch { state.choose(120, b) }
            launch { state.choose(132, b) }
            launch { state.save { assertEquals(107, it.saved); assertEquals(26, it.unmatched) } }
        }
        val stored = sessions.load("draft")!!
        assertEquals(b, stored.items[120].selected)
        assertEquals(b, stored.items[132].selected)
        assertNotNull(stored.committedPlaylistId)
        assertEquals(1, playlists.observePlaylists().first().size)
    }

    @Test fun failedChoiceAndCommitCallbacksRetainConfirmedDraftAndNeverEmitSuccess() = runBlocking {
        val dir = folder.newFolder()
        val sessions = ImportSessionStore(dir)
        sessions.save(draft())
        val state = ImportReviewDraftState(coordinator(sessions))
        state.resume("draft")
        val before = state.session
        val blocker = File(dir, "draft.import.json.tmp")
        blocker.mkdir()
        File(blocker, "busy").writeText("busy")
        state.choose(120, b)
        assertEquals(before, state.session)
        assertNotNull(state.error)
        assertNull(state.choiceMessage)
        state.save { fail("No success on a failed write") }
        assertEquals(before, state.session)
        assertNotNull(state.error)
        assertFalse(state.loading)
        assertFalse(state.choosing)
        assertTrue(playlists.observePlaylists().first().isEmpty())
    }

    @Test fun canceledReadCannotResurrectProgressOrReplaceAResumedDraft() = runBlocking {
        val sessions = ImportSessionStore(folder.newFolder())
        sessions.save(draft())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val spotify = SpotifyRepository(SpotifyApiTransport {
            SpotifyApiPage("""<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Late","trackList":[{"title":"Song","subtitle":"Artist","duration":180000}]}}}}}}</script>""")
        })
        val coordinator = ImportCoordinator(playlists, provenance, sessions, spotify, search = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            listOf(a)
        })
        val state = ImportReviewDraftState(coordinator)
        val job = launch { state.read(link) }
        started.await()
        assertTrue(state.loading)
        state.cancelReading()
        state.resume("draft")
        release.complete(Unit)
        job.join()
        assertFalse(state.loading)
        assertNull(state.progress)
        assertEquals("draft", state.session!!.id)
        assertNull(state.error)
        assertEquals(listOf("draft"), sessions.pending().map { it.id })
    }
}
