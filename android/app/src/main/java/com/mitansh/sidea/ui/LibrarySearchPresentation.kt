package com.mitansh.sidea.ui

internal enum class LibrarySearchEmptyKind { NONE, NO_RESULTS }

enum class LibrarySortOption(val displayName: String) {
    RECENTS("Recents"),
    RECENTLY_ADDED("Recently added"),
    ALPHABETICAL("Alphabetical"),
    CREATOR("Creator")
}

internal fun librarySearchBackEnabled(searching: Boolean, query: String, playerVisible: Boolean): Boolean =
    !playerVisible && (searching || query.isNotEmpty())

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
            playlists: List<PlaylistCard>,
            query: String,
            sortOption: LibrarySortOption = LibrarySortOption.RECENTS,
            playlistOnly: Boolean = false,
        ): LibrarySearchPresentation {
            val searchTerm = query.trim()
            val filtered = playlists.filter { it.name.contains(searchTerm, true) }
            val visible = when (sortOption) {
                LibrarySortOption.ALPHABETICAL -> filtered.sortedBy { it.name.lowercase() }
                LibrarySortOption.CREATOR -> filtered.sortedBy { it.name.lowercase() }
                LibrarySortOption.RECENTLY_ADDED,
                LibrarySortOption.RECENTS -> filtered.sortedByDescending { it.createdAtEpochMs }
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

        fun from(
            playlists: List<PlaylistCard>,
            query: String,
            nameSort: Boolean,
            playlistOnly: Boolean,
        ): LibrarySearchPresentation = from(
            playlists,
            query,
            if (nameSort) LibrarySortOption.ALPHABETICAL else LibrarySortOption.RECENTLY_ADDED,
            playlistOnly,
        )
    }
}
