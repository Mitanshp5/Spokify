package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import java.util.Locale

/**
 * Turns upstream song-radio candidates into a queue.
 *
 * Upstream relevance is the starting point, not the answer. The official Spotify app was inspected
 * for comparison: its radio is a server-side protocol that returns a *station* for a seed, with no
 * client-side ranking to copy, so this mixer does the work a smaller app must do itself — version
 * and duplicate filtering, artist diversity, and preference weighting from real listening.
 *
 * Signals are deliberately small and reversible. A track the user liked, finished or replayed is
 * promoted; a track abandoned early is demoted; nothing is banned, because an early skip is weak
 * evidence and a bad skip can cost a good song.
 */
object SongRadioMixer {

    /** How many tracks of the recent window an artist may occupy before it is considered crowded. */
    const val ARTIST_WINDOW = 10

    /** How many of those tracks may be by the same artist. */
    const val ARTIST_WINDOW_CAP = 2

    /** How many recent picks must not share an artist. */
    const val SPACING_WINDOW = 2

    fun mix(
        upstream: List<Track>,
        seed: Track,
        favorites: Set<String>,
        earlySkips: Map<String, Int>,
        artistWeights: Map<String, Int> = emptyMap(),
        excludedIds: Set<String> = emptySet(),
        excludedTracks: List<Track> = emptyList(),
        limit: Int,
        taste: TasteProfile = TasteProfile.Empty,
    ): List<Track> {
        val limit = limit.coerceIn(0, MAX_MIX_SIZE)
        if (limit == 0) return emptyList()
        val excluded = (excludedTracks + seed).mapTo(mutableSetOf(), ::recording)
        val seenIds = mutableSetOf(seed.id).apply { addAll(excludedIds); addAll(excludedTracks.map(Track::id)) }
        val candidates = upstream.filter { it.id.isNotBlank() && it.title.isNotBlank() && seenIds.add(it.id) && recording(it) !in excluded }.distinctBy(::recording)
        if (candidates.isEmpty()) return emptyList()
        val ranked = candidates.mapIndexed { index, track ->
            Triple(track, index, score(track, index, favorites, earlySkips, artistWeights, taste))
        }.sortedWith(compareBy({ it.third }, { it.second }))
        return diversify(ranked, taste.recentArtists, limit)
    }

    /**
     * Scores one candidate. Lower is better, so the upstream order is the baseline and every
     * preference is a subtraction from it.
     *
     * An explicit skip outweighs a taste boost, because the user acted on this specific track
     * while a taste signal is only a general preference. A skip never removes the track from the
     * candidate set; it only makes it less likely to be chosen.
     */
    private fun score(
        track: Track,
        index: Int,
        favorites: Set<String>,
        earlySkips: Map<String, Int>,
        artistWeights: Map<String, Int>,
        taste: TasteProfile,
    ): Long {
        var score = index.toLong()
        if (track.uid in favorites || track.id in favorites) score -= FAVORITE_BOOST
        val skips = (earlySkips[track.id] ?: 0).coerceAtLeast(0)
        score += skips * SKIP_PENALTY
        val boost = taste.trackBoost(track)
        score -= if (skips > 0) boost.coerceAtMost(MAX_BOOST_WHEN_SKIPPED) else boost
        score += (artistWeights[artistKey(track)] ?: 0).coerceIn(0, MAX_ARTIST_WEIGHT) * ARTIST_PENALTY
        return score
    }

    /**
     * Spreads the selected tracks so one artist does not fill the queue.
     *
     * Two rules apply, and they answer different questions. The *spacing* rule keeps the last two
     * picks from sharing an artist, so the queue alternates rather than repeating. The *cap* rule
     * limits how many of the recent window may belong to one artist, and it only starts to bite
     * once the window is full. The window carries the artists that were played most recently, so a
     * batch continues the variety of the whole session rather than only of its own batch.
     *
     * When every remaining candidate would break the spacing rule, the least crowded one is taken
     * instead of stopping, so the queue still fills.
     */
    private fun diversify(
        ranked: List<Triple<Track, Int, Long>>,
        recentArtists: List<String>,
        limit: Int,
    ): List<Track> {
        val remaining = ranked.toMutableList()
        val window = ArrayDeque(recentArtists.filter { it.isNotEmpty() })
        val picks = mutableListOf<String>()
        val result = mutableListOf<Track>()
        while (result.size < limit && remaining.isNotEmpty()) {
            val crowded = window.size >= ARTIST_WINDOW
            val spaced = picks.takeLast(SPACING_WINDOW).toSet()
            val index = remaining.indexOfFirst { (track, _, _) ->
                val artist = artistKey(track)
                if (artist.isEmpty()) return@indexOfFirst true
                if (artist in spaced) return@indexOfFirst false
                if (crowded && window.count { it == artist } >= ARTIST_WINDOW_CAP) return@indexOfFirst false
                true
            }
            val chosen = remaining.removeAt(if (index < 0) 0 else index)
            val artist = artistKey(chosen.first)
            picks += artist
            if (window.size >= ARTIST_WINDOW) window.removeFirst()
            window.addLast(artist)
            result += chosen.first
        }
        return result
    }

    /** Normalizes an artist name so radio ranking and persisted feedback agree on one key. */
    fun normalizeArtist(artist: String) = artist.lowercase(Locale.ROOT).replace(IDENTITY_PATTERN, "")

    private fun artistKey(track: Track) = normalizeArtist(track.artist)

    private fun recording(track: Track) = artistKey(track) + "|" + track.title.lowercase(Locale.ROOT).replace(IDENTITY_PATTERN, "")

    private const val FAVORITE_BOOST = 3
    private const val SKIP_PENALTY = 10L
    private const val ARTIST_PENALTY = 5L
    private const val MAX_ARTIST_WEIGHT = 10
    private const val MAX_MIX_SIZE = 100

    /** A skipped track keeps only a small part of its taste boost. */
    private const val MAX_BOOST_WHEN_SKIPPED = 2L
    private val IDENTITY_PATTERN = Regex("[^\\p{L}\\p{N}]")
}

/**
 * What the user actually likes, derived from real listening rather than taps.
 *
 * The official app's recommendation model is server-side and was not recovered from the shipped
 * client, so this is an explicit, inspectable approximation: counts of completed listens, repeats
 * and plays, plus the artists of recently played tracks.
 */
data class TasteProfile(
    /** Play count per durable track id. */
    val playCounts: Map<String, Int> = emptyMap(),
    /** Completed-listen count per durable track id. */
    val completedCounts: Map<String, Int> = emptyMap(),
    /** Play count per normalized artist. */
    val artistPlayCounts: Map<String, Int> = emptyMap(),
    /** Artists of the most recently played tracks, used to keep the queue varied. */
    val recentArtists: List<String> = emptyList(),
) {

    /** How much this track is preferred, in the same units as one upstream position. */
    fun trackBoost(track: Track): Long {
        val plays = playCounts[track.uid] ?: playCounts[track.id] ?: 0
        val completed = completedCounts[track.uid] ?: completedCounts[track.id] ?: 0
        val artist = SongRadioMixer.normalizeArtist(track.artist)
        val artistPlays = artistPlayCounts[artist] ?: 0
        if (plays <= 0 && artistPlays <= 0) return 0
        // Completion and repetition matter more than raw play count, but an artist the user has
        // never played cannot be a strong signal on its own.
        val completedBonus = completed * COMPLETED_WEIGHT
        val repeatBonus = (plays - 1).coerceAtLeast(0) * REPEAT_WEIGHT
        // The artist's own plays count only once, so the track's own plays are not counted twice.
        val artistBonus = (artistPlays - plays).coerceAtLeast(0) * ARTIST_WEIGHT
        return (completedBonus + repeatBonus + artistBonus).coerceAtMost(MAX_BOOST)
    }

    companion object {
        val Empty = TasteProfile()

        const val COMPLETED_WEIGHT = 2L
        const val REPEAT_WEIGHT = 1L
        const val ARTIST_WEIGHT = 1L

        /** A track the user loves cannot outrank the whole upstream list on its own. */
        const val MAX_BOOST = 12L
    }
}
