package com.mitansh.sidea.ui

import com.mitansh.sidea.data.imports.ImportCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportReviewSaveFeedbackTest {
    @Test fun successfulCommitShowsAccurateCountsOnLibraryAndOpensActualDestination() = runBlocking {
        val events = mutableListOf<String>()
        showImportSaveFeedback(ImportCoordinator.CommitResult("actual-playlist-17", 106, 27, false),
            onLibrary = { events += "Library" },
            showMessage = { message, action ->
                assertEquals(listOf("Library"), events)
                assertEquals("Saved 106 songs · 27 not saved", message)
                assertEquals("Open", action)
                events += "feedback"
                true
            },
            onOpenPlaylist = { events += it })
        assertEquals(listOf("Library", "feedback", "actual-playlist-17"), events)
    }

    @Test fun idempotentSaveRetainsCountsAndDismissalDoesNotOpenPlaylist() = runBlocking {
        var shown = false
        showImportSaveFeedback(ImportCoordinator.CommitResult("existing-id", 106, 27, true), {},
            { message, action ->
                shown = true
                assertEquals("Already saved 106 songs · 27 not saved", message)
                assertEquals("Open", action)
                false
            }, { fail("Dismissed snackbar must not navigate") })
        assertTrue(shown)
    }

    @Test fun failedOrMissingDestinationNeverClaimsSuccess() = runBlocking {
        for (result in listOf(ImportCoordinator.CommitResult(null, 0, 27, false, "disk full"),
            ImportCoordinator.CommitResult(null, 106, 27, false))) {
            showImportSaveFeedback(result, { fail("Stay on review") },
                { _, _ -> fail("No false success snackbar"); false }, { fail("No unknown destination") })
        }
    }
}
