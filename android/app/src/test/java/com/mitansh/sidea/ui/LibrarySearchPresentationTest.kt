package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchPresentationTest {
    @Test fun unmatchedQueryHasExplicitNoResults() {
        val result = LibrarySearchPresentation.from(
            listOf(PlaylistCard("1", "Road trip", 3, null)), "zzznomatch987", false, false,
        )
        assertTrue(result.playlists.isEmpty())
        assertFalse(result.showLikedSongs || result.showOnRepeat || result.showOfflineBackup || result.showRecentlyPlayed)
        assertEquals(LibrarySearchEmptyKind.NO_RESULTS, result.emptyKind)
    }

    @Test fun builtinOnlyMatchIsAResult() {
        val result = LibrarySearchPresentation.from(emptyList(), "repeat", false, false)
        assertTrue(result.playlists.isEmpty())
        assertTrue(result.showOnRepeat)
        assertFalse(result.showLikedSongs || result.showOfflineBackup || result.showRecentlyPlayed)
        assertEquals(LibrarySearchEmptyKind.NONE, result.emptyKind)
    }

    @Test fun trimsQueryForPlaylistsAndBuiltinsWithoutChangingInput() {
        val query = "  road  "
        val playlist = PlaylistCard("1", "Road trip", 3, null)
        assertEquals(listOf(playlist), LibrarySearchPresentation.from(listOf(playlist), query, false, false).playlists)
        assertEquals("  road  ", query)
        assertTrue(LibrarySearchPresentation.from(emptyList(), "  liked  ", false, false).showLikedSongs)
    }

    @Test fun blankQueryWithNoUserPlaylistsStillHasBuiltinResults() {
        for (query in listOf("", "  \t ")) {
            val result = LibrarySearchPresentation.from(emptyList(), query, false, false)
            assertEquals(LibrarySearchEmptyKind.NONE, result.emptyKind)
            assertTrue(result.showLikedSongs && result.showOnRepeat && result.showOfflineBackup && result.showRecentlyPlayed)
        }
    }

    @Test fun eachBuiltinAlonePreventsNoResults() {
        for (query in listOf("Liked Songs", "On Repeat", "Offline Backup", "Recently played")) {
            val result = LibrarySearchPresentation.from(emptyList(), query, false, false)
            assertEquals(query, LibrarySearchEmptyKind.NONE, result.emptyKind)
            assertEquals(1, listOf(result.showLikedSongs, result.showOnRepeat, result.showOfflineBackup, result.showRecentlyPlayed).count { it })
        }
    }

    @Test fun playlistFilterHidesOnlyNonPlaylistBuiltins() {
        val playlist = PlaylistCard("1", "Offline mix", 2, null)
        val result = LibrarySearchPresentation.from(listOf(playlist), "", false, true)
        assertEquals(listOf(playlist), result.playlists)
        assertTrue(result.showLikedSongs && result.showOnRepeat)
        assertFalse(result.showOfflineBackup || result.showRecentlyPlayed)
        for (query in listOf("Offline Backup", "Recently played")) {
            assertEquals(LibrarySearchEmptyKind.NO_RESULTS, LibrarySearchPresentation.from(emptyList(), query, false, true).emptyKind)
        }
    }

    @Test fun matchingRetainsCaseInsensitiveContainsAndAccentSemantics() {
        val playlist = PlaylistCard("1", "Café nights", 1, null)
        assertEquals(listOf(playlist), LibrarySearchPresentation.from(listOf(playlist), "CAFÉ", false, false).playlists)
        assertEquals(LibrarySearchEmptyKind.NO_RESULTS, LibrarySearchPresentation.from(listOf(playlist), "cafe", false, false).emptyKind)
    }

    @Test fun nameSortIsStableAndDoesNotDeduplicateNames() {
        val rows = listOf(
            PlaylistCard("b", "Beta", 0, null),
            PlaylistCard("a1", "Alpha", 1, null),
            PlaylistCard("a2", "alpha", 2, null),
            PlaylistCard("a3", "Alpha", 3, null),
        )
        assertEquals(listOf("a1", "a2", "a3", "b"), LibrarySearchPresentation.from(rows, "", true, false).playlists.map { it.id })
        assertEquals(listOf("b", "a1", "a2", "a3"), rows.map { it.id })
    }

    @Test fun recentSortIsDescendingAndStableForTies() {
        val rows = listOf(
            PlaylistCard("old", "Same", 0, null, 1),
            PlaylistCard("new1", "Same", 0, null, 3),
            PlaylistCard("new2", "Same", 0, null, 3),
        )
        assertEquals(listOf("new1", "new2", "old"), LibrarySearchPresentation.from(rows, "same", false, false).playlists.map { it.id })
    }
}
