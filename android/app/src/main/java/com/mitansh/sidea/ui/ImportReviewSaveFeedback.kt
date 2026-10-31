package com.mitansh.sidea.ui

import com.mitansh.sidea.data.imports.ImportCoordinator

/** Destination feedback, independent of whether the review composable is still mounted. */
internal suspend fun showImportSaveFeedback(
    result: ImportCoordinator.CommitResult,
    onLibrary: () -> Unit,
    showMessage: suspend (message: String, action: String) -> Boolean,
    onOpenPlaylist: (String) -> Unit,
) {
    val playlistId = result.playlistId ?: return
    if (result.error != null) return
    onLibrary()
    val message = (if (result.alreadyCommitted) "Already saved ${result.saved} songs" else "Saved ${result.saved} songs") +
        if (result.unmatched > 0) " · ${result.unmatched} not saved" else ""
    if (showMessage(message, "Open")) onOpenPlaylist(playlistId)
}
