package com.mitansh.sidea.data.offline

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAutomaticStoreTest {

    private var stored = ""
    private val store = PendingAutomaticStore(
        read = { stored },
        write = { value -> stored = value },
    )

    private fun track(id: String, title: String = "Song $id") = Track(id, title, "Artist $id")

    @Test
    fun `a song below the play threshold is not remembered`() {
        store.remember(track("a"), 2, lastPlayedAtEpochMs = 1_000L)

        assertTrue(store.isEmpty())
    }

    @Test
    fun `a qualified song is remembered with its metadata`() {
        store.remember(track("a", "Real Title"), 3, lastPlayedAtEpochMs = 1_000L)

        val candidate = store.candidates().single()
        assertEquals("a", candidate.track.id)
        assertEquals("Real Title", candidate.track.title)
        assertEquals(3, candidate.playCount)
    }

    @Test
    fun `candidates are ordered by how often the song was played`() {
        store.remember(track("a"), 3, lastPlayedAtEpochMs = 1_000L)
        store.remember(track("b"), 9, lastPlayedAtEpochMs = 1_000L)
        store.remember(track("c"), 5, lastPlayedAtEpochMs = 1_000L)

        assertEquals(listOf("b", "c", "a"), store.candidates().map { it.track.id })
    }

    @Test
    fun `hearing a song again replaces its earlier entry`() {
        store.remember(track("a"), 3, lastPlayedAtEpochMs = 1_000L)
        store.remember(track("a"), 7, lastPlayedAtEpochMs = 1_000L)

        assertEquals(1, store.size())
        assertEquals(7, store.candidates().single().playCount)
    }

    @Test
    fun `a saved song is forgotten`() {
        store.remember(track("a"), 3, lastPlayedAtEpochMs = 1_000L)
        store.remember(track("b"), 4, lastPlayedAtEpochMs = 1_000L)

        store.forget("a")

        assertEquals(listOf("b"), store.candidates().map { it.track.id })
    }

    @Test
    fun `an unreadable entry is skipped rather than breaking the list`() {
        store.remember(track("a"), 3, lastPlayedAtEpochMs = 1_000L)
        stored = stored + "\ngarbage\n|broken|\n"

        assertEquals(listOf("a"), store.candidates().map { it.track.id })
    }

    @Test
    fun `the store survives a reload`() {
        store.remember(track("a"), 5, lastPlayedAtEpochMs = 1_000L)

        val reloaded = PendingAutomaticStore(read = { stored }, write = { stored = it })

        assertEquals("a", reloaded.candidates().single().track.id)
    }

    @Test
    fun `clearing removes every candidate`() {
        store.remember(track("a"), 3, lastPlayedAtEpochMs = 1_000L)
        store.remember(track("b"), 4, lastPlayedAtEpochMs = 1_000L)

        store.clear()

        assertTrue(store.isEmpty())
    }
}
