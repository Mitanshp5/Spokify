package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    private class Storage : SearchHistoryStorage {
        var value = "[]"
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    @Test fun historySurvivesReconstructionWithSourceQualifiedSongsAndEscapedQueries() {
        val storage = Storage()
        val history = SearchHistory(storage)
        val song = Track("same", "Song \"title\"", "Artist", 123000, "https://example.test/cover", "youtube")
        history.query("a \"quote\" & 日本語")
        history.song(song)
        history.song(song.copy(source = "other"))
        val reloaded = SearchHistory(storage).list()
        assertEquals(3, reloaded.size)
        assertEquals(song.copy(source = "other"), reloaded[0].track)
        assertEquals(song, reloaded[1].track)
        assertEquals("a \"quote\" & 日本語", reloaded[2].query)
    }
    @Test fun repeatedQueriesMoveToFrontWithoutDuplicatesAndTheListIsBounded() {
        val history = SearchHistory()
        repeat(30) { history.query("query $it") }
        history.query("QUERY   20")
        assertEquals(SearchHistory.LIMIT, history.list().size)
        assertEquals("QUERY 20", history.list().first().query)
        assertEquals(1, history.list().count { it.key == "query:query 20" })
    }
    @Test fun individualRemovalAndClearRemainRemovedAfterRestart() {
        val storage = Storage()
        val history = SearchHistory(storage)
        history.query("one"); history.query("two")
        history.remove(history.list().last().key)
        assertEquals("two", SearchHistory(storage).list().single().query)
        history.clear()
        assertTrue(SearchHistory(storage).list().isEmpty())
    }
    @Test fun malformedStoredHistoryAndBlankQueriesDoNotBreakSearch() {
        val storage = Storage().apply { value = "not json" }
        val history = SearchHistory(storage)
        history.query("  ")
        assertTrue(history.list().isEmpty())
        history.query("valid")
        assertEquals("valid", SearchHistory(storage).list().single().query)
    }
    @Test fun missingSongMetadataDoesNotDiscardValidQueryRows() {
        val storage = Storage().apply { value = """[{"track":{}},{"query":"valid"},{"track":{"id":"song","title":"Song"}}]""" }
        val rows = SearchHistory(storage).list()
        assertEquals(2, rows.size)
        assertEquals("valid", rows.first().query)
        assertEquals("youtube:song", rows.last().track?.uid)
    }
}
