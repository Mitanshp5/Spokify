package com.mitansh.sidea.data.spotify

import java.net.URI
import java.text.Normalizer
import com.mitansh.sidea.core.Track
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.CancellationException

enum class SpotifyCompleteness { COMPLETE, PARTIAL, UNKNOWN }
data class SpotifyPlaylistPreview(val id: String, val title: String, val tracks: List<SpotifySourceTrack>, val completeness: SpotifyCompleteness, val declaredCount: Int? = null, val warning: String? = null)
data class SpotifySourceTrack(val position: Int, val title: String, val artist: String, val durationMs: Long, val sourceId: String?, val savedAt: String = "")

object SpotifyPlaylist {
    private val idPattern = Regex("[A-Za-z0-9]{22}")
    private val script = Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    fun parseId(input: String): String? {
        val value = input.trim()
        if (value.startsWith("spotify:")) return value.split(':').takeIf { it.size == 3 && it[0] == "spotify" && it[1] == "playlist" }?.last()?.takeIf(idPattern::matches)
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "open.spotify.com" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        val parts = uri.path.split('/').filter(String::isNotEmpty)
        val start = if (parts.firstOrNull()?.matches(Regex("intl-[A-Za-z0-9-]+")) == true) 1 else 0
        val rest = parts.drop(start)
        val playlistIndex = if (rest.firstOrNull() == "embed") 1 else 0
        if (rest.getOrNull(playlistIndex) != "playlist" || rest.size != playlistIndex + 2) return null
        return rest.last().takeIf(idPattern::matches)
    }

    fun parseEmbed(html: String): SpotifyPlaylistPreview {
        require(html.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Spotify response too large" }
        val json = script.find(html)?.groupValues?.get(1) ?: error("Spotify embed layout not recognized")
        val root = runCatching { JsonParser.any().from(json) as? Map<*, *> }.getOrNull() ?: error("Malformed Spotify embed JSON")
        val entity = (((root["props"] as? Map<*, *>)?.get("pageProps") as? Map<*, *>)?.get("state") as? Map<*, *>)
            ?.get("data").let { (it as? Map<*, *>)?.get("entity") as? Map<*, *> } ?: error("Spotify playlist data missing")
        val list = entity["trackList"] as? List<*> ?: error("Spotify track list missing")
        val tracks = list.take(MAX_TRACKS).mapIndexed { i, item ->
            val row = item as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val uri = row["uri"] as? String
            SpotifySourceTrack(i, row["title"] as? String ?: "", row["subtitle"] as? String ?: "", (row["duration"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0, uri?.removePrefix("spotify:track:")?.takeIf(idPattern::matches))
        }
        val declared = (entity["trackCount"] ?: entity["totalTracks"] ?: entity["total"] as? Number)?.toString()?.toIntOrNull()
        val completeness = when {
            list.size > MAX_TRACKS -> SpotifyCompleteness.PARTIAL
            declared == null -> SpotifyCompleteness.UNKNOWN
            declared <= tracks.size -> SpotifyCompleteness.COMPLETE
            else -> SpotifyCompleteness.PARTIAL
        }
        return SpotifyPlaylistPreview("", entity["name"] as? String ?: entity["title"] as? String ?: "", tracks, completeness, declared,
            "Spotify embed track list was capped".takeIf { list.size > MAX_TRACKS })
    }

    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_TRACKS = 2000
}

enum class SpotifyMatchStatus { AUTO, REVIEW, MISSING }
data class SpotifyTrackMatch(val source: SpotifySourceTrack, val alternatives: List<Track>, val selected: Track?, val status: SpotifyMatchStatus)
data class SpotifyImportPreview(val playlist: SpotifyPlaylistPreview, val matches: List<SpotifyTrackMatch>, val failedSearches: Int = 0) {
    /**
     * The rows to save, in the playlist's own order.
     *
     * Explicit user selections are kept positional, and repeated source rows stay repeated. A row
     * the user picked by hand is saved even when the automatic match was not confident.
     */
    fun selectedTracks(selections: Map<Int, Track> = emptyMap()): List<Track?> =
        matches.map { selections[it.source.position] ?: it.selected }
}

suspend fun matchSpotifyPlaylist(
    playlist: SpotifyPlaylistPreview,
    onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    search: suspend (String) -> List<Track>,
): SpotifyImportPreview {
    var failedSearches = 0
    val matches = playlist.tracks.map { source ->
        onProgress(source.position + 1, playlist.tracks.size)
        if (source.title.isBlank()) return@map SpotifyTrackMatch(source, emptyList(), null, SpotifyMatchStatus.MISSING)
        val results = try {
            search(listOf(source.title, source.artist).filter(String::isNotBlank).joinToString(" "))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failedSearches++
            emptyList()
        }
        val candidates = results.distinctBy { it.uid }
            .map { it to scoreSpotifyCandidate(source, it) }.sortedByDescending { it.second }
        val first = candidates.firstOrNull()
        val runnerUp = candidates.drop(1).firstOrNull()
        // Two things make a row uncertain: a close runner-up, and a candidate whose artist credits
        // differ from the source while the title is the same. The second case is a genuine
        // ambiguity that the metadata alone cannot settle, so the row is sent for review rather
        // than accepted or dropped.
        val ambiguous = candidates.any { isAmbiguousCredit(source, it.first) }
        val closeAlternative = first != null && runnerUp != null && first.second - runnerUp.second < 0.10
        val status = when {
            ambiguous -> if (first != null && first.second >= REVIEW_FLOOR) SpotifyMatchStatus.REVIEW
            else SpotifyMatchStatus.MISSING
            first == null || first.second < 0.68 -> SpotifyMatchStatus.MISSING
            closeAlternative || first.second < 0.84 -> SpotifyMatchStatus.REVIEW
            else -> SpotifyMatchStatus.AUTO
        }
        SpotifyTrackMatch(source, candidates.take(5).map { it.first }, first?.first.takeIf { status == SpotifyMatchStatus.AUTO }, status)
    }
    if (failedSearches > 0 && failedSearches == playlist.tracks.count { it.title.isNotBlank() }) {
        throw SpotifyImportException(SpotifyFailure.NETWORK, "Song matching is unavailable. Check your connection and try again.")
    }
    return SpotifyImportPreview(playlist, matches, failedSearches)
}

/**
 * Whether a candidate with the same title but different artist credits is genuinely ambiguous.
 *
 * "Get Lucky" by "Daft Punk" and "Get Lucky" by "Daft Punk & Pharrell Williams" can be the same
 * recording or a different one; the metadata alone cannot tell, so such a row goes for review. A
 * completely different artist is not ambiguous, it is a different song, and stays unmatched.
 */
private fun isAmbiguousCredit(source: SpotifySourceTrack, candidate: Track): Boolean {
    val title = normalizeWords(source.title)
    if (title.isEmpty() || normalizeWords(candidate.title) != title) return false
    val sourceArtists = artistTokens(source.artist)
    val candidateArtists = artistTokens(candidate.artist)
    if (sourceArtists.isEmpty() || candidateArtists.isEmpty()) return false
    if (sourceArtists == candidateArtists) return false
    // Unrelated artists are a different song; only shared credits make a row ambiguous.
    return sourceArtists.intersect(candidateArtists).isNotEmpty()
}

private fun artistTokens(value: String): Set<String> =
    normalizeWords(value).split(' ').filter { it.isNotEmpty() }.toSet()

private fun normalizeWords(value: String): String = value
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

/**
 * The score an ambiguous row must still reach to be worth showing for review.
 *
 * Below this the candidate is simply a different recording, so the row is reported as missing
 * rather than offered as a choice.
 */
private const val REVIEW_FLOOR = 0.6

/**
 * Scores how well a playable candidate matches a source track.
 *
 * The score is deliberately strict. A wrong recording is worse than an unmatched row, because the
 * user can review an unmatched row but never hears that the song they saved was replaced by a
 * cover. Version markers, artist agreement and duration all have to agree; a partial artist name
 * alone is not enough to accept a match.
 */
private fun scoreSpotifyCandidate(source: SpotifySourceTrack, candidate: Track): Double {
    fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val versions = listOf("live", "remix", "cover", "acoustic", "instrumental", "edit", "karaoke", "remaster")
    fun versionSet(s: String) = versions.filter { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) }.toSet()
    val title = norm(source.title)
    val candidateTitle = norm(candidate.title)
    val artist = norm(source.artist)
    val candidateArtist = norm(candidate.artist)
    val versionMismatch = versionSet(source.title) != versionSet(candidate.title)
    val titleScore = when {
        title == candidateTitle && title.isNotEmpty() -> 1.0
        title.isNotEmpty() && candidateTitle.isNotEmpty() &&
            (candidateTitle.contains(title) || title.contains(candidateTitle)) -> 0.7
        else -> 0.0
    }
    // A partial artist name is weak evidence; it only helps when the title already agrees.
    val artistScore = when {
        artist.isEmpty() -> 0.3
        artist == candidateArtist -> 1.0
        else -> 0.0
    }
    val durationScore = when {
        source.durationMs <= 0 || candidate.durationMs <= 0 -> 0.2
        else -> {
            val difference = kotlin.math.abs(source.durationMs - candidate.durationMs)
            // A recording that is far off in length is a different recording, not the same song.
            when {
                difference <= 5_000L -> 1.0
                difference <= 15_000L -> 0.6
                else -> 0.0
            }
        }
    }
    val score = titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    return if (versionMismatch) (score - 0.35).coerceAtLeast(0.0) else score
}
