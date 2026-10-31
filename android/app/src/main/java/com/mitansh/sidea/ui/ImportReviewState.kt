package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportedItem

enum class ImportReviewFilter { ATTENTION, READY, ALL }

/** Filtering never sorts or deduplicates source rows. Position, not recording id, is row identity. */
fun importReviewItems(
    items: List<ImportedItem>,
    selections: Map<Int, Track>,
    filter: ImportReviewFilter,
): List<ImportedItem> = items.filter {
    val ready = importReviewChoice(it, selections) != null
    when (filter) {
        ImportReviewFilter.ATTENTION -> !ready
        ImportReviewFilter.READY -> ready
        ImportReviewFilter.ALL -> true
    }
}

fun importReviewChoice(item: ImportedItem, selections: Map<Int, Track>): Track? =
    selections[item.position] ?: item.selected

/** Keep every offered recording, deduplicating only source-qualified candidate identities. */
fun importReviewCandidates(item: ImportedItem): List<Track> =
    (item.alternatives + listOfNotNull(item.selected)).distinctBy { it.uid }

fun importReviewTitle(title: String): String = title.ifBlank { "Unavailable track" }

fun importReviewDuration(durationMs: Long): String {
    if (durationMs <= 0) return "Duration unknown"
    val seconds = durationMs / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
