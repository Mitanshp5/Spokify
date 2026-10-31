package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportedItem
import org.junit.Assert.*
import org.junit.Test

class ImportReviewTest {
    private val a = Track("same", "Song", "Artist", 180000, "https://example.test/candidate")
    private val b = a.copy(source = "other")
    private val items = listOf(
        ImportedItem(0, "repeat", "Song", "Artist", 180000, selected = a),
        ImportedItem(1, null, "", "", 0, alternatives = listOf(a, b)),
        ImportedItem(2, "repeat", "Song", "Artist", 180000, alternatives = listOf(a, b), status = ImportedItem.Status.REVIEW),
        ImportedItem(3, "repeat", "Song", "Artist", 180000, alternatives = listOf(a), status = ImportedItem.Status.REVIEW),
    )

    @Test fun attentionGroupsReviewAndMissingInOriginalOrderIncludingRepeatedRows() {
        assertEquals(listOf(1, 2, 3), importReviewItems(items, emptyMap(), ImportReviewFilter.ATTENTION).map { it.position })
        assertEquals(listOf(0), importReviewItems(items, emptyMap(), ImportReviewFilter.READY).map { it.position })
        assertEquals(items, importReviewItems(items, emptyMap(), ImportReviewFilter.ALL))
    }

    @Test fun manualChoiceMovesOnlyItsSourcePositionToReadyWithoutChangingSourceOrder() {
        val choices = mapOf(2 to b, 1 to a)
        assertEquals(listOf(3), importReviewItems(items, choices, ImportReviewFilter.ATTENTION).map { it.position })
        val ready = importReviewItems(items, choices, ImportReviewFilter.READY)
        assertEquals(listOf(0, 1, 2), ready.map { it.position })
        assertEquals(listOf(a.uid, a.uid, b.uid), ready.map { importReviewChoice(it, choices)?.uid })
        assertNull(items[2].selected)
        assertEquals(listOf(0, 1, 2, 3), importReviewItems(items, choices, ImportReviewFilter.ALL).map { it.position })
    }

    @Test fun candidateIdentityIncludesSourceAndEveryAlternativeRemainsAvailable() {
        assertEquals(listOf(a, b), importReviewCandidates(items[1]))
        assertEquals(listOf(a, b), importReviewCandidates(items[1].copy(selected = a)))
        val many = (0..9).map { a.copy(id = "$it") }
        assertEquals(many, importReviewCandidates(items[1].copy(alternatives = many)))
    }

    @Test fun unavailableSourceAndUnknownDurationHaveHonestLabels() {
        assertEquals("Unavailable track", importReviewTitle("  "))
        assertEquals("Song", importReviewTitle("Song"))
        assertEquals("Duration unknown", importReviewDuration(0))
        assertEquals("3:01", importReviewDuration(181000))
    }
}
