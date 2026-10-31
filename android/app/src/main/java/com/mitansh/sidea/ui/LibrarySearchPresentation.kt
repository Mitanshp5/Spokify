package com.mitansh.sidea.ui

internal enum class LibrarySearchEmptyKind { NONE, NO_RESULTS }

internal data class LibrarySearchPresentation(
    val playlists: List<PlaylistCard>,
    val showLikedSongs: Boolean,
    val showOnRepeat: Boolean,
    val showOfflineBackup: Boolean,
    val showRecentlyPlayed: Boolean,
    val emptyKind: LibrarySearchEmptyKind,
) {
    companion object {
        fun from(
            playlists: List<PlaylistCard>, query: String, nameSort: Boolean, playlistOnly: Boolean,
        ): LibrarySearchPresentation {
            val searchTerm = query.trim()
            val visible = playlists.filter { it.name.contains(searchTerm, true) }.let { rows ->
                if (nameSort) rows.sortedBy { it.name.lowercase() }
                else rows.sortedByDescending { it.createdAtEpochMs }
            }
            val result = LibrarySearchPresentation(
                visible,
                "Liked Songs".contains(searchTerm, true),
                "On Repeat".contains(searchTerm, true),
                !playlistOnly && "Offline Backup".contains(searchTerm, true),
                !playlistOnly && "Recently played".contains(searchTerm, true),
                LibrarySearchEmptyKind.NONE,
            )
            return if (visible.isEmpty() && !result.showLikedSongs && !result.showOnRepeat &&
                !result.showOfflineBackup && !result.showRecentlyPlayed
            ) result.copy(emptyKind = LibrarySearchEmptyKind.NO_RESULTS) else result
        }
    }
}
