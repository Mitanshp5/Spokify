package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchCoordinatorTest {
    @Test fun typingIsDebouncedAndClearingCancelsPendingResults() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = SearchCoordinator(backgroundScope) { query -> calls += query; listOf(Track(query, query, "Artist")) }
        coordinator.updateQuery("s")
        advanceTimeBy(200)
        coordinator.updateQuery("song")
        advanceTimeBy(349)
        runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("song"), calls)
        assertEquals("song", coordinator.state.value.results.single().id)
        assertTrue(coordinator.state.value.recent.isEmpty())
        coordinator.updateQuery("new")
        coordinator.updateQuery("")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("song"), calls)
        assertTrue(coordinator.state.value.results.isEmpty())
        assertFalse(coordinator.state.value.loading)
    }

    @Test fun clearingQueryRejectsAResultThatFinishesDespiteCancellation() = runTest {
        val result = CompletableDeferred<List<Track>>()
        val coordinator = SearchCoordinator(backgroundScope) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { result.await() }
        }
        coordinator.search("old")
        runCurrent()
        coordinator.updateQuery("")
        result.complete(listOf(Track("old", "Old", "Artist")))
        runCurrent()
        assertEquals("", coordinator.state.value.query)
        assertTrue(coordinator.state.value.results.isEmpty())
        assertFalse(coordinator.state.value.loading)
    }

    @Test fun explicitSearchAndSelectionAppearInHistoryAndCanBeRemoved() = runTest {
        val coordinator = SearchCoordinator(backgroundScope) { emptyList() }
        coordinator.search("Djo")
        runCurrent()
        coordinator.recordSelection(Track("song", "Song", "Djo"))
        assertEquals(2, coordinator.state.value.recent.size)
        val key = coordinator.state.value.recent.first().key
        coordinator.removeRecent(key)
        assertEquals("Djo", coordinator.state.value.recent.single().query)
        coordinator.clearHistory()
        assertTrue(coordinator.state.value.recent.isEmpty())
    }

    @Test fun currentSearchFailureClearsLoadingAndCanBeRetried() = runTest {
        var fail = true
        val coordinator = SearchCoordinator(backgroundScope) {
            if (fail) throw java.io.IOException("offline") else listOf(Track("ok", "Song", "Artist"))
        }
        coordinator.search("song")
        runCurrent()
        assertFalse(coordinator.state.value.loading)
        assertEquals("offline", coordinator.state.value.error)
        fail = false
        coordinator.search()
        runCurrent()
        assertNull(coordinator.state.value.error)
        assertEquals("ok", coordinator.state.value.results.single().id)
    }
    @Test fun latestSearchWinsAndSupersededFailureDoesNotReplaceIt() = runTest {
        val first = CompletableDeferred<List<Track>>()
        val second = CompletableDeferred<List<Track>>()
        var firstCancelled = false
        val coordinator = SearchCoordinator(backgroundScope) { query ->
            if (query == "first") try {
                first.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                firstCancelled = true
                throw cancelled
            } else second.await()
        }
        coordinator.search("first")
        runCurrent()
        coordinator.search("second")
        runCurrent()
        second.complete(listOf(Track("new", "New", "Artist")))
        runCurrent()
        first.complete(listOf(Track("stale", "Stale", "Artist")))
        runCurrent()

        assertEquals("new", coordinator.state.value.results.single().id)
        assertFalse(coordinator.state.value.loading)
        assertNull(coordinator.state.value.error)
        assertTrue(firstCancelled)
    }
}
