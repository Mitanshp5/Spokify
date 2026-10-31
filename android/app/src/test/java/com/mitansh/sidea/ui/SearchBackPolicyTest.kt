package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class SearchBackPolicyTest {
    @Test fun visiblePlayerTakesBackBeforeFocusedSearchResults() {
        assertFalse(searchBackEnabled(focused = true, playerVisible = true))
    }

    @Test fun visiblePlayerTakesBackBeforeBlankFocusedSearch() {
        // Search remains active even when its query is blank.
        assertFalse(searchBackEnabled(focused = true, playerVisible = true))
    }

    @Test fun focusedSearchHandlesBackWithoutPlayer() {
        assertTrue(searchBackEnabled(focused = true, playerVisible = false))
    }

    @Test fun expandedWithoutCurrentTrackDoesNotHideSearch() {
        val expanded = true
        val hasCurrentTrack = false
        assertTrue(searchBackEnabled(focused = true, playerVisible = expanded && hasCurrentTrack))
    }

    @Test fun currentTrackWithoutExpansionDoesNotHideSearch() {
        val expanded = false
        val hasCurrentTrack = true
        assertTrue(searchBackEnabled(focused = true, playerVisible = expanded && hasCurrentTrack))
    }

    @Test fun unfocusedSearchDoesNotHandleBack() {
        assertFalse(searchBackEnabled(focused = false, playerVisible = false))
        assertFalse(searchBackEnabled(focused = false, playerVisible = true))
    }

    @Test fun dismissingPlayerReturnsBackOwnershipToStillFocusedSearch() {
        assertTrue(searchBackEnabled(focused = true, playerVisible = false))
        assertFalse(searchBackEnabled(focused = true, playerVisible = true))
        assertTrue(searchBackEnabled(focused = true, playerVisible = false))
    }
}
