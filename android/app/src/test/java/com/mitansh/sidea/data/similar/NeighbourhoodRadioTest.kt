package com.mitansh.sidea.data.similar

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighbourhoodRadioTest {

    private class FakeMusicBrainz(
        private val artistIds: Map<String, String> = emptyMap(),
        private val recordingIds: Map<String, String> = emptyMap(),
    ) : MusicBrainzRepository() {
        override fun artistId(artistName: String): String? = artistIds[artistName]
        override fun recordingId(artistName: String, title: String): String? = recordingIds["$artistName|$title"]
    }

    private class FakeSimilarity(
        private val artists: List<SimilarityRepository.SimilarArtist> = emptyList(),
        private val recordings: List<SimilarityRepository.SimilarRecording> = emptyList(),
    ) : SimilarityRepository() {
        override fun similarArtists(artistMbid: String, limit: Int) = artists.take(limit)
        override fun similarRecordings(recordingMbid: String, limit: Int) = recordings.take(limit)
    }

    private val seed = Track("seed", "Blinding Lights", "The Weeknd")

    @Test
    fun `track neighbours come before artist neighbours`() {
        val radio = NeighbourhoodRadio(
            FakeMusicBrainz(artistIds = mapOf("The Weeknd" to "mbid-artist"), recordingIds = mapOf("The Weeknd|Blinding Lights" to "mbid-recording")),
            FakeSimilarity(
                artists = listOf(SimilarityRepository.SimilarArtist("a1", "Daft Punk", 7000)),
                recordings = listOf(SimilarityRepository.SimilarRecording("r1", "Slide", "Calvin Harris", "Album", 900)),
            ),
        )

        val candidates = radio.candidatesFor(seed)

        assertEquals(NeighbourhoodRadio.Reason.SIMILAR_TRACK, candidates.first().reason)
        assertEquals("Slide", candidates.first().title)
    }

    @Test
    fun `the seed itself is never proposed`() {
        val radio = NeighbourhoodRadio(
            FakeMusicBrainz(recordingIds = mapOf("The Weeknd|Blinding Lights" to "mbid")),
            FakeSimilarity(
                recordings = listOf(SimilarityRepository.SimilarRecording("r1", "Blinding Lights", "The Weeknd", "After Hours", 900)),
            ),
        )

        assertTrue(radio.candidatesFor(seed).isEmpty())
    }

    @Test
    fun `artist neighbours are used when there is no recording match`() {
        val radio = NeighbourhoodRadio(
            FakeMusicBrainz(artistIds = mapOf("The Weeknd" to "mbid-artist")),
            FakeSimilarity(artists = listOf(SimilarityRepository.SimilarArtist("a1", "Daft Punk", 7000))),
        )

        val candidates = radio.candidatesFor(seed)

        assertEquals(1, candidates.size)
        assertEquals(NeighbourhoodRadio.Reason.SIMILAR_ARTIST, candidates.first().reason)
        assertEquals("Daft Punk", candidates.first().artist)
    }

    @Test
    fun `candidates are ordered by the service score`() {
        val radio = NeighbourhoodRadio(
            FakeMusicBrainz(artistIds = mapOf("The Weeknd" to "mbid")),
            FakeSimilarity(
                artists = listOf(
                    SimilarityRepository.SimilarArtist("a1", "Low", 100),
                    SimilarityRepository.SimilarArtist("a2", "High", 9000),
                ),
            ),
        )

        val candidates = radio.candidatesFor(seed)

        assertEquals("High", candidates.first().artist)
    }

    @Test
    fun `an unknown artist produces no candidates rather than an error`() {
        val radio = NeighbourhoodRadio(FakeMusicBrainz(), FakeSimilarity())

        assertTrue(radio.candidatesFor(seed).isEmpty())
    }

    @Test
    fun `a limit of zero produces nothing`() {
        val radio = NeighbourhoodRadio(
            FakeMusicBrainz(artistIds = mapOf("The Weeknd" to "mbid")),
            FakeSimilarity(artists = listOf(SimilarityRepository.SimilarArtist("a1", "Daft Punk", 7000))),
        )

        assertTrue(radio.candidatesFor(seed, limit = 0).isEmpty())
    }
}
