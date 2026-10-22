package com.mitansh.sidea.data.youtube

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.mitansh.sidea.core.Track
import java.util.Locale

data class RadioPage(val tracks: List<Track>, val continuation: String?)

class SongRadioParseException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

object SongRadioParser {
    private val identityPattern = Regex("[^\\p{L}\\p{N}]")
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun parse(json: String, seed: Track): RadioPage {
        val root = try {
            JsonParser.`object`().from(json)
        } catch (error: Exception) {
            throw SongRadioParseException("Invalid song radio JSON", error)
        } catch (error: StackOverflowError) {
            throw SongRadioParseException("Invalid song radio JSON", error)
        }
        val panel = root.getObject("contents")?.getObject("singleColumnMusicWatchNextResultsRenderer")
            ?.getObject("tabbedRenderer")?.getObject("watchNextTabbedResultsRenderer")?.getArray("tabs")
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.getObject("tabRenderer")?.getObject("content")?.getObject("musicQueueRenderer")?.getObject("content")?.getObject("playlistPanelRenderer") }
            ?: root.getObject("continuationContents")?.getObject("playlistPanelContinuation")
        val contents = panel?.getArray("contents") ?: return RadioPage(emptyList(), null)
        val seenIds = mutableSetOf(seed.id)
        val seenRecordings = mutableSetOf(recording(seed))
        val tracks = panelTracks(contents).filter { seenIds.add(it.id) && seenRecordings.add(recording(it)) }
        val continuation = panel.getArray("continuations")?.firstNotNullOfOrNull { item ->
            (item as? JsonObject)?.getObject("nextRadioContinuationData")?.getString("continuation")
                ?: (item as? JsonObject)?.getObject("nextContinuationData")?.getString("continuation")
        } ?: contents.firstNotNullOfOrNull { item ->
            (item as? JsonObject)?.getObject("continuationItemRenderer")?.getObject("continuationEndpoint")
                ?.getObject("continuationCommand")?.getString("token")
        }
        return RadioPage(tracks.toList(), continuation?.takeIf(String::isNotBlank))
    }

    private fun panelTracks(contents: JsonArray): List<Track> = contents.mapNotNull { item ->
        val row = (item as? JsonObject)?.getObject("playlistPanelVideoRenderer") ?: return@mapNotNull null
        val id = row.getString("videoId")?.takeIf(videoIdPattern::matches) ?: return@mapNotNull null
        val title = runs(row.getObject("title")).joinToString("").trim()
        if (title.isBlank() || row.has("unplayableText")) return@mapNotNull null
        val creditRuns = row.getObject("longBylineText")?.getArray("runs").orEmpty()
        val artist = creditRuns.takeWhile { (it as? JsonObject)?.getString("text")?.contains('•') != true }
            .mapNotNull { (it as? JsonObject)?.getString("text") }.joinToString("").trim().trimEnd(',', '&').trim()
        val durationText = runs(row.getObject("lengthText")).joinToString("")
        val duration = durationText.split(':').fold(0L) { total, part ->
            val number = part.toLongOrNull() ?: return@mapNotNull null
            total * 60 + number
        } * 1000
        val artwork = row.getObject("thumbnail")?.getArray("thumbnails")?.lastOrNull()
            ?.let { (it as? JsonObject)?.getString("url") }
        Track(id, title, artist, duration, artwork)
    }

    private fun runs(value: JsonObject?): List<String> = value?.getArray("runs")
        ?.mapNotNull { (it as? JsonObject)?.getString("text") }.orEmpty()

    private fun recording(track: Track) = normalize(track.artist) + "|" + normalize(track.title)
    private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(identityPattern, "")
}
