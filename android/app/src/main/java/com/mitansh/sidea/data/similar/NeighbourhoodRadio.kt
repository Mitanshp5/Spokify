package com.mitansh.sidea.data.similar

import com.mitansh.sidea.core.Track

/**
 * Turns a seed song into candidate songs that listeners actually pair with it.
 *
 * The official app keeps its radio model on the server, so it cannot be copied. This uses the
 * public collaborative-filtering neighbourhoods instead: the seed's artist and recording are looked
 * up in MusicBrainz, and ListenBrainz supplies the artists and tracks that listeners play alongside
 * them, each with the service's own relevance score.
 *
 * The candidate list is ordered by that score, so the radio has a relevance signal it did not
 * have before, while the existing mixer still applies the user's own preferences on top.
 */
class NeighbourhoodRadio(
    private val musicBrainz: MusicBrainzRepository,
    private val similarity: SimilarityRepository,
) {

    /** One candidate song with the reason it was proposed. */
    data class Candidate(
        val title: String,
        val artist: String,
        val score: Int,
        val reason: Reason,
    )

    enum class Reason { SIMILAR_TRACK, SIMILAR_ARTIST }

    /**
     * The songs that belong next to [seed].
     *
     * Track-level neighbours come first because they are the closest match, and artist-level
     * neighbours fill in behind them so a seed with few neighbours still produces a radio.
     */
    fun candidatesFor(seed: Track, limit: Int = 25): List<Candidate> {
        if (limit <= 0) return emptyList()
        val results = mutableListOf<Candidate>()

        val recordingMbid = musicBrainz.recordingId(seed.artist, seed.title)
        if (recordingMbid != null) {
            similarity.similarRecordings(recordingMbid, limit).forEach { similar ->
                if (similar.title.equals(seed.title, ignoreCase = true) &&
                    similar.artist.equals(seed.artist, ignoreCase = true)
                ) {
                    return@forEach
                }
                results += Candidate(similar.title, similar.artist, similar.score, Reason.SIMILAR_TRACK)
            }
        }

        val artistMbid = musicBrainz.artistId(seed.artist)
        if (artistMbid != null) {
            similarity.similarArtists(artistMbid).forEach { similar ->
                results += Candidate("", similar.name, similar.score, Reason.SIMILAR_ARTIST)
            }
        }

        return results
            .filter { it.title.isNotBlank() || it.artist.isNotBlank() }
            // Track neighbours are the closest match, so they come first; the artist neighbours
            // fill in behind them, ordered by their own score within their group.
            .sortedWith(compareBy<Candidate> { it.reason.ordinal }.thenByDescending { it.score })
            .take(limit)
    }
}
