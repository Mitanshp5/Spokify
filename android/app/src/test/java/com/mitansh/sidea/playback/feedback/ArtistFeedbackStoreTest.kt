package com.mitansh.sidea.playback.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistFeedbackStoreTest {

    private class MemoryStorage : FeedbackStorage {
        private var data: Map<String, Int> = emptyMap()
        override fun load(): Map<String, Int> = data
        override fun save(weights: Map<String, Int>) {
            data = weights
        }
    }

    @Test
    fun `repeated skips raise one normalized artist weight`() {
        val store = ArtistFeedbackStore(MemoryStorage())

        store.recordSkip("B.a.n.d!")
        store.recordSkip("BAND")

        assertEquals(mapOf("band" to 2), store.weights())
    }

    @Test
    fun `qualified listening softens old negative feedback`() {
        val store = ArtistFeedbackStore(MemoryStorage())
        store.recordSkip("Artist")
        store.recordSkip("Artist")

        store.recordQualified("artist")

        assertEquals(mapOf("artist" to 1), store.weights())
    }

    @Test
    fun `weights survive a reload from the same storage`() {
        val storage = MemoryStorage()
        ArtistFeedbackStore(storage).recordSkip("Artist")

        assertEquals(mapOf("artist" to 1), ArtistFeedbackStore(storage).weights())
    }

    @Test
    fun `weights stay bounded by count and magnitude`() {
        val store = ArtistFeedbackStore(MemoryStorage())
        repeat(200) { store.recordSkip("Artist $it") }

        val weights = store.weights()
        assertTrue("expected bounded entry count, got ${weights.size}", weights.size <= 50)
        assertTrue("expected bounded magnitude", weights.values.all { it in 1..10 })
    }

    @Test
    fun `a blank artist is ignored`() {
        val store = ArtistFeedbackStore(MemoryStorage())

        store.recordSkip("   ")

        assertTrue(store.weights().isEmpty())
    }
}
