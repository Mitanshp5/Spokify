package com.mitansh.sidea.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.MemoryPlaylistDao
import com.mitansh.sidea.data.library.PlaylistRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistDialogStateTest {
    @Test fun blankNamesDoNotCommitOrDismiss() = runTest {
        for (name in listOf("", " \t\n ")) {
            var commits = 0
            var closes = 0
            PlaylistDialogState("create", null).confirm(this, name, { commits++ }, { closes++ })
            runCurrent()
            assertEquals(0, commits)
            assertEquals("Blank input must keep the dialog open", 0, closes)
        }
    }

    @Test fun waitsForActualSuspendCompletionBeforeClosing() = runTest {
        val completion = CompletableDeferred<Unit>()
        var closes = 0
        PlaylistDialogState("create", null).confirm(this, " sample ", { completion.await() }, { closes++ })
        runCurrent()
        assertEquals("Dialog must remain open during the write", 0, closes)
        completion.complete(Unit)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test fun validNameIsTrimmedWithoutChangingIdentity() = runTest {
        var saved: PlaylistDialogOperation? = null
        PlaylistDialogState("rename", "original-id").confirm(this, "  sample  ", { saved = it }, {})
        runCurrent()
        assertEquals(PlaylistDialogOperation("rename", "original-id", "sample"), saved)
    }

    @Test fun rapidSecondConfirmDoesNotStartAnotherWrite() = runTest {
        val state = PlaylistDialogState("create", null)
        val completion = CompletableDeferred<Unit>()
        var commits = 0
        var closes = 0
        repeat(2) { state.confirm(this, "sample", { commits++; completion.await() }, { closes++ }) }
        runCurrent()
        completion.complete(Unit)
        runCurrent()
        assertEquals(1, commits)
        assertEquals(1, closes)
    }

    @Test fun delayedFailureStaysOpenAndAllowsRetry() = runTest {
        val state = PlaylistDialogState("rename", "original-id")
        val completion = CompletableDeferred<Unit>()
        var closes = 0
        val attempts = mutableListOf<PlaylistDialogOperation>()
        val draft = "  retained draft  "
        val write: suspend (PlaylistDialogOperation) -> Unit = {
            attempts += it
            if (attempts.size == 1) { completion.await(); error("Database unavailable") }
        }
        state.confirm(this, draft, write, { closes++ })
        assertTrue(state.busy)
        assertFalse(state.canConfirm("retained draft"))
        runCurrent()
        assertEquals(0, closes)
        completion.complete(Unit)
        runCurrent()
        assertEquals(0, closes)
        assertEquals("Couldn't save playlist. Try again.", state.error)
        assertFalse(state.busy)
        assertEquals("  retained draft  ", draft)
        state.confirm(this, draft, write, { closes++ })
        runCurrent()
        assertEquals(1, closes)
        assertEquals(List(2) { PlaylistDialogOperation("rename", "original-id", "retained draft") }, attempts)
        assertNull(state.error)
    }

    @Test fun validationDisablesEmptyAndWhitespaceForCreateAndRename() {
        for (action in listOf("create", "rename")) {
            val state = PlaylistDialogState(action, "id")
            assertFalse(state.canConfirm(""))
            assertFalse(state.canConfirm(" \t\n"))
            assertTrue(state.canConfirm(" sample "))
        }
    }

    @Test fun cancelBeforeConfirmDoesNotMutateAnything() = runTest {
        val state = PlaylistDialogState("delete", "id")
        var closes = 0
        var commits = 0
        state.dismiss { closes++ }
        state.confirm(this, "", { commits++ }, { closes++ })
        runCurrent()
        assertEquals(1, closes)
        assertEquals(0, commits)
    }

    @Test fun cancelAndBackAreIgnoredUntilWriteFinishes() = runTest {
        val state = PlaylistDialogState("rename", "id")
        val completion = CompletableDeferred<Unit>()
        var closes = 0
        state.confirm(this, "sample", { completion.await() }, { closes++ })
        repeat(2) { state.dismiss { closes++ } }
        runCurrent()
        assertEquals(0, closes)
        completion.complete(Unit)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test fun duplicateNamesRemainAllowedThroughRepository() = runTest {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        repeat(2) {
            PlaylistDialogState("create", null).confirm(this, " sample ", { it.commitTo(repository) }, {})
            runCurrent()
        }
        assertEquals(listOf("sample", "sample"), repository.observePlaylists().first().map { it.name })
        assertEquals(2, repository.observePlaylists().first().map { it.id }.distinct().size)
    }

    @Test fun renameCapturesDraftAndIdentityBeforeCoroutineStarts() = runTest {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        var selected = repository.create("original")
        val original = selected
        val other = repository.create("other")
        var draft = " renamed "
        PlaylistDialogState("rename", selected).confirm(this, draft, { it.commitTo(repository) }, {})
        selected = other
        draft = "changed later"
        runCurrent()
        assertEquals("renamed", repository.observePlaylists().first().first { it.id == original }.name)
        assertEquals("other", repository.observePlaylists().first().first { it.id == selected }.name)
        assertEquals("changed later", draft)
    }

    @Test fun deleteRequiresExplicitConfirmAndOnlyRemovesTargetPlaylist() = runTest {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val song = Track("song", "Song", "Artist")
        val target = repository.create("target", listOf(song))
        val other = repository.create("other", listOf(song))
        val state = PlaylistDialogState("delete", target)
        assertEquals(2, repository.observePlaylists().first().size)
        assertEquals("This removes the playlist, not your songs. Liked songs and downloads are kept.", PLAYLIST_DELETE_SCOPE)
        state.confirm(this, "", { it.commitTo(repository) }, {})
        runCurrent()
        assertEquals(listOf(other), repository.observePlaylists().first().map { it.id })
        assertTrue(repository.observeEntries(target).first().isEmpty())
        assertEquals(listOf(song), repository.observeEntries(other).first().map { it.track })
    }

    @Test fun missingRenameOrDeleteIdentityCannotCommit() = runTest {
        var commits = 0
        for (action in listOf("rename", "delete")) {
            val state = PlaylistDialogState(action, null)
            assertFalse(state.canConfirm("sample"))
            state.confirm(this, "sample", { commits++ }, {})
        }
        runCurrent()
        assertEquals(0, commits)
    }

    @Test fun failedDeleteDoesNotRunSuccessNavigationAndCanBeCancelled() = runTest {
        val state = PlaylistDialogState("delete", "target")
        var successes = 0
        var dismissals = 0
        state.confirm(this, "", { error("Disk unavailable") }, { successes++ })
        runCurrent()
        assertEquals(0, successes)
        assertEquals("Couldn't delete playlist. Try again.", state.error)
        assertTrue(state.canConfirm(""))
        state.dismiss { dismissals++ }
        assertEquals(1, dismissals)
    }
}
