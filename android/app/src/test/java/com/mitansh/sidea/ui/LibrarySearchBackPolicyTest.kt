package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchBackPolicyTest {
    @Test fun visiblePlayerSuppressesHiddenSearchHandler() {
        assertFalse(librarySearchBackEnabled(searching = true, query = "Bhavya", playerVisible = true))
    }

    @Test fun visiblePlayerSuppressesBlankOpenSearchHandler() {
        assertFalse(librarySearchBackEnabled(searching = true, query = "", playerVisible = true))
    }

    @Test fun activeQueryHandlesBackWithoutPlayer() {
        assertTrue(librarySearchBackEnabled(searching = true, query = "Bhavya", playerVisible = false))
        assertTrue(librarySearchBackEnabled(searching = false, query = "Bhavya", playerVisible = false))
    }

    @Test fun blankOpenSearchHandlesBackWithoutPlayer() {
        assertTrue(librarySearchBackEnabled(searching = true, query = "", playerVisible = false))
    }

    @Test fun closedEmptySearchDoesNotHandleBack() {
        assertFalse(librarySearchBackEnabled(searching = false, query = "", playerVisible = false))
        assertFalse(librarySearchBackEnabled(searching = false, query = "", playerVisible = true))
    }

    @Test fun returningFromPlayerReenablesSearchBackWithoutMutatingQuery() {
        val searching = true
        val query = "Bhavya"
        assertTrue(librarySearchBackEnabled(searching, query, playerVisible = false))
        assertFalse(librarySearchBackEnabled(searching, query, playerVisible = true))
        assertTrue(librarySearchBackEnabled(searching, query, playerVisible = false))
        assertEquals("Bhavya", query)
        assertTrue(searching)
    }
}
