package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileTest {

    private fun track(id: String, artist: String = "Artist") = Track(id, "Song $id", artist)

    @Test
    fun `an unplayed track gets no boost`() {
        assertEquals(0L, TasteProfile.Empty.trackBoost(track("a")))
    }

    @Test
    fun `a completed listen promotes the track`() {
        val taste = TasteProfile(
            playCounts = mapOf("a" to 1),
            completedCounts = mapOf("a" to 1),
        )

        assertTrue(taste.trackBoost(track("a")) > 0)
    }

    @Test
    fun `a repeated track is promoted more than a single listen`() {
        val single = TasteProfile(playCounts = mapOf("a" to 1)).trackBoost(track("a"))
        val repeated = TasteProfile(playCounts = mapOf("a" to 5)).trackBoost(track("a"))

        assertTrue("expected repeats to count for more, got $repeated vs $single", repeated > single)
    }

    @Test
    fun `a played artist lifts that artist's other tracks`() {
        val taste = TasteProfile(
            playCounts = mapOf("known" to 1),
            artistPlayCounts = mapOf("artist" to 6),
        )

        assertTrue(taste.trackBoost(track("unknown", "Artist")) > 0)
    }

    @Test
    fun `the boost is bounded so a favourite cannot outrank the whole upstream list`() {
        val taste = TasteProfile(
            playCounts = mapOf("a" to 500),
            completedCounts = mapOf("a" to 500),
            artistPlayCounts = mapOf("artist" to 500),
        )

        assertTrue(taste.trackBoost(track("a")) <= TasteProfile.MAX_BOOST)
    }

    @Test
    fun `a track the user likes is promoted above a more relevant unknown one`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(
            Track("a", "Song A", "Unknown"),
            Track("b", "Song B", "Loved"),
        )
        val taste = TasteProfile(
            playCounts = mapOf("b" to 4),
            completedCounts = mapOf("b" to 4),
            artistPlayCounts = mapOf("loved" to 4),
        )

        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 2, taste = taste)

        assertEquals("b", output.first().id)
    }

    @Test
    fun `the mixer still excludes the seed and queued recordings`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "A"), Track("b", "Song B", "B"))

        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 5)

        assertEquals(listOf("a", "b"), output.map { it.id })
    }

    @Test
    fun `a recently played artist does not fill the whole batch`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(
            Track("a", "Song A", "Loved"),
            Track("b", "Song B", "Loved"),
            Track("c", "Song C", "Other"),
            Track("d", "Song D", "Third"),
        )
        val taste = TasteProfile(
            playCounts = mapOf("a" to 3),
            artistPlayCounts = mapOf("loved" to 3),
            recentArtists = listOf("loved"),
        )

        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 4, taste = taste)

        assertEquals(4, output.size)
        assertTrue(
            "expected the other artists to be picked up, got ${output.map { it.artist }}",
            output.any { it.artist == "Other" } && output.any { it.artist == "Third" },
        )
    }

    @Test
    fun `an artist cap does not stop the queue from filling`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf("a", "b", "c").map { Track(it, "Song $it", "Same Artist") }

        assertEquals(3, SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 3).size)
    }

    @Test
    fun `an explicit skip is still demoted below a track with no signal`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "Artist A"), Track("b", "Song B", "Artist B"))

        val output = SongRadioMixer.mix(upstream, seed, emptySet(), mapOf("a" to 1), limit = 2)

        assertEquals(listOf("b", "a"), output.map { it.id })
    }

    @Test
    fun `no candidates yields an empty result rather than an error`() {
        assertTrue(SongRadioMixer.mix(emptyList(), Track("seed", "S", "A"), emptySet(), emptyMap(), limit = 5).isEmpty())
    }

    @Test
    fun `a limit of zero yields nothing`() {
        assertTrue(SongRadioMixer.mix(listOf(Track("a", "A", "B")), Track("seed", "S", "A"), emptySet(), emptyMap(), limit = 0).isEmpty())
    }

    @Test
    fun `taste never promotes a track that was explicitly skipped`() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "Loved"), Track("b", "Song B", "Other"))
        val taste = TasteProfile(playCounts = mapOf("a" to 5), completedCounts = mapOf("a" to 5))

        val output = SongRadioMixer.mix(upstream, seed, emptySet(), mapOf("a" to 1), limit = 2, taste = taste)

        assertEquals("b", output.first().id)
        assertFalse(output.isEmpty())
    }
}
