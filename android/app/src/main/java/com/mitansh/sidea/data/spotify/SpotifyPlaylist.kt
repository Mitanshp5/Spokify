package com.mitansh.sidea.data.spotify

import java.net.URI
import java.math.BigDecimal
import java.text.Normalizer
import com.mitansh.sidea.core.Track
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.CancellationException

enum class SpotifyCompleteness { COMPLETE, PARTIAL, UNKNOWN }
data class SpotifyPlaylistPreview(val id: String, val title: String, val tracks: List<SpotifySourceTrack>, val completeness: SpotifyCompleteness, val declaredCount: Int? = null, val warning: String? = null)
data class SpotifySourceTrack(val position: Int, val title: String, val artist: String, val durationMs: Long, val sourceId: String?, val savedAt: String = "", val artworkUrl: String? = null)

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
            val albumArt = row["albumArt"] as? Map<*, *>
            val sources = albumArt?.get("sources") as? List<*>
            val artwork = sources?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("url") as? String }
                ?.takeIf { it.isNotBlank() }
            SpotifySourceTrack(i, row["title"] as? String ?: "", row["subtitle"] as? String ?: "", (row["duration"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0, uri?.removePrefix("spotify:track:")?.takeIf(idPattern::matches), artworkUrl = artwork)
        }
        val rawDeclared = entity["trackCount"] ?: entity["totalTracks"] ?: entity["total"]
        val declared = rawDeclared?.let {
            require(it is Number || it is String) { "Invalid Spotify playlist count" }
            BigDecimal(it.toString()).intValueExact().also { count ->
                require(count >= 0 && count >= list.size) { "Contradictory Spotify playlist count" }
            }
        }
        val completeness = when {
            list.size > MAX_TRACKS -> SpotifyCompleteness.PARTIAL
            declared == null -> SpotifyCompleteness.UNKNOWN
            declared == tracks.size -> SpotifyCompleteness.COMPLETE
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
        // Only plausible recordings can create credit ambiguity. A distant-duration result must
        // not veto stronger evidence, but unexplained extra performers still require review.
        val ambiguous = candidates.any { it.second >= REVIEW_FLOOR && isAmbiguousCredit(source, it.first) }
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
 * Dropped credits are common in the song catalog; added credits are not explained by that
 * convention. Only shared whole names (not shared surname tokens) can create ambiguity.
 */
private fun isAmbiguousCredit(source: SpotifySourceTrack, candidate: Track): Boolean {
    val title = recordingTitle(source.title)
    if (title.isEmpty() || recordingTitle(candidate.title) != title) return false
    val sourceArtists = recordingArtists(source.artist, source.title)
    val candidateArtists = recordingArtists(candidate.artist, candidate.title)
    if (sourceArtists.isEmpty() || candidateArtists.isEmpty()) return false
    if (sourceArtists.containsAll(candidateArtists)) return false
    return sourceArtists.intersect(artistTokens(candidate.artist)).isNotEmpty()
}

private val featureLabel = Regex("(?i)[\\[(]\\s*(?:feat\\.?|featuring|with)\\s+([^\\])]+)[\\])]")

private fun recordingArtists(artist: String, title: String): Set<String> =
    artistTokens(artist) + featureLabel.findAll(title).flatMap { artistTokens(it.groupValues[1]) }.toSet()

private fun artistTokens(value: String): Set<String> =
    value.split(Regex(",|&|;|(?i)\\bfeat\\.?\\s|\\s+and\\s+"))
        .map(::normalizeWords).filter { it.isNotEmpty() }.toSet()

private fun normalizeWords(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
    // Latin accents are presentation differences; Indic vowel signs are part of the word.
    .replace(Regex("(?<=\\p{IsLatin})\\p{M}+"), "")
    .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

/** Remove only identified presentation metadata, never arbitrary parenthesized version names. */
private fun recordingTitle(value: String): String = normalizeWords(value
    .replace(featureLabel, " ")
    .replace(Regex("(?i)[\\[(]\\s*(?:from\\s+|movie\\s*:\\s*)([^\\])]+)[\\])]")) { " ${filmSuffix(it.groupValues[1])} " }
    .replace(Regex("(?i)\\s+-\\s+(?:from\\s+|movie\\s*:\\s*)(.+)$")) { " ${filmSuffix(it.groupValues[1])} " }
    .replace(Regex("(?i)\\s*[\\[(]\\s*(?:official\\s+)?(?:lyric(?:s)?(?:\\s+video)?|audio|music\\s+video|video)\\s*[\\])]"), " ")
    .replace(Regex("(?i)\\s*[-|]\\s*(?:official\\s+)?(?:lyric(?:s)?(?:\\s+video)?|audio|music\\s+video|video)\\s*$"), " "))

/** A quoted film ends at its closing quote, NOT at the end of the metadata bracket. */
private fun filmSuffix(value: String): String {
    val text = value.trim()
    val closingQuote = when (text.firstOrNull()) {
        '"' -> '"'
        '\'' -> '\''
        '“' -> '”'
        else -> null
    }
    if (closingQuote != null) {
        val end = text.indexOf(closingQuote, 1)
        return if (end >= 0) text.substring(end + 1) else text
    }
    // Unquoted Movie:/From labels also occur. Retain explicit language/version evidence.
    val marker = Regex("(?i)\\b(?:hindi|tamil|telugu|gujarati|malayalam|kannada|bengali|punjabi|marathi|english|version|live|remix|acoustic|instrumental|karaoke|cover|edit|remaster|reprise|unplugged|slowed|sped|lofi)\\b").find(text)
    return marker?.let { text.substring(it.range.first) } ?: ""
}

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
    val versions = listOf("live", "remix", "cover", "acoustic", "instrumental", "edit", "karaoke", "remaster",
        "reprise", "sped up", "slowed", "reverb", "lofi", "lo fi", "unplugged")
    fun versionSet(s: String) = versions.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(recordingTitle(s)) }.toSet()
    val title = recordingTitle(source.title)
    val candidateTitle = recordingTitle(candidate.title)
    val artist = normalizeWords(source.artist)
    val sourceArtists = recordingArtists(source.artist, source.title)
    val candidateArtists = recordingArtists(candidate.artist, candidate.title)
    val versionMismatch = versionSet(source.title) != versionSet(candidate.title)
    if (versionMismatch) return 0.0
    if (source.durationMs > 0 && candidate.durationMs > 0 &&
        kotlin.math.abs(source.durationMs - candidate.durationMs) > 30_000L) return 0.0
    val titleScore = when {
        title == candidateTitle && title.isNotEmpty() -> 1.0
        else -> 0.0
    }
    // Whole-name credit subsets explain primary-performer catalog metadata, not name fragments.
    val artistScore = when {
        artist.isEmpty() -> 0.3
        candidateArtists.isNotEmpty() && sourceArtists.containsAll(candidateArtists) -> 1.0
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
    return if (source.durationMs <= 0 || candidate.durationMs <= 0) score.coerceAtMost(0.82) else score
}
