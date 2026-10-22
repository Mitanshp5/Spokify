package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportedItem
import com.mitansh.sidea.data.imports.ImportedPlaylist
import com.mitansh.sidea.data.imports.ImportSessionStore
import org.junit.Assert.*
import org.junit.Test

class ImportReviewLabelTest {
    private val session = ImportSessionStore.Session("test", ImportedPlaylist.Source.SPOTIFY, "id", "Mix", "", null,
        declaredCount = null, complete = false, items = listOf(
            ImportedItem(0, null, "Review", "Artist", 100, status = ImportedItem.Status.REVIEW),
            ImportedItem(1, null, "Missing", "Artist", 100, status = ImportedItem.Status.MISSING),
            ImportedItem(2, null, "Chosen", "Artist", 100, selected = Track("song", "Chosen", "Artist"), status = ImportedItem.Status.REVIEW),
        ))

    @Test fun resolvingManualChoicesUpdatesOutstandingCountsWithoutClaimingCompleteness() {
        assertEquals("Read 3 songs · total unknown · 1 need a check, 1 not found", completenessLabel(session))
        assertEquals("Read 3 songs · total unknown · 1 not found", completenessLabel(session, setOf(0)))
        assertEquals("Read 3 songs · total unknown", completenessLabel(session, setOf(0, 1)))
    }

    @Test fun resolvingRowsDoesNotChangeTheSourceReadCount() {
        val partial = session.copy(declaredCount = 10)
        assertEquals("Read 3 of 10 songs", completenessLabel(partial, setOf(0, 1)))
        assertEquals("Read all 3 songs", completenessLabel(partial.copy(complete = true), setOf(0, 1)))
    }
}
