package com.mitansh.sidea.data.imports

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import java.net.URI

/**
 * Reads a YouTube or YouTube Music playlist.
 *
 * The playlist is read through the extractor's own playlist reader, so the same parsing that powers
 * a single video also powers a whole playlist, including its later pages. Each song keeps its own
 * video id, so nothing has to be matched by title.
 */
class YouTubePlaylistReader {

    /** One song read from a playlist, with the position it had in the source. */
    data class Item(
        val position: Int,
        val trackId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val artworkUrl: String?,
    )

    /** The playlist as it was read, with an honest completeness statement. */
    data class Playlist(
        val id: String,
        val title: String,
        val author: String,
        val artworkUrl: String?,
        val items: List<Item>,
        val declaredCount: Int?,
        val complete: Boolean,
    )

    /** Parses a playlist link or id and returns the playlist id, or null when it is not one. */
    fun parseId(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (ID_PATTERN.matches(value)) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in YOUTUBE_HOSTS) return null
        val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val marker = parts.indexOfFirst { it == "playlist" || it == "watch" || it == "browse" }
        if (marker < 0) return null
        val candidate = parts.getOrNull(marker + 1) ?: return null
        val id = candidate.substringBefore('?').substringBefore('&')
        return id.takeIf { ID_PATTERN.matches(it) }
    }

    /**
     * Reads the playlist, following its pages.
     *
     * Reading stops at the item limit or when the source has no further page. The result says
     * whether the whole playlist was read, so the import can report it honestly.
     */
    fun read(id: String, maxItems: Int = 2000): Playlist {
        val extractor = extractorFor(id)
        val items = mutableListOf<Item>()
        var page: org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.stream.StreamInfoItem>? = extractor.initialPage
        while (page != null && items.size < maxItems) {
            for (info in page.items) {
                val videoId = videoIdOf(info.url)
                items += Item(
                    position = items.size,
                    trackId = videoId,
                    title = info.name,
                    artist = info.uploaderName,
                    durationMs = info.duration,
                    artworkUrl = info.thumbnailUrl.takeIf { it.isNotBlank() },
                )
                if (items.size >= maxItems) break
            }
            val next = page.nextPage
            page = if (next == null) null else runCatching { extractor.getPage(next) }.getOrNull()        }
        val declared = runCatching { extractor.streamCount }.getOrNull()?.takeIf { it > 0 }
        return Playlist(
            id = id,
            title = runCatching { extractor.name }.getOrDefault("").trim(),
            author = runCatching { extractor.uploaderName }.getOrDefault("").trim(),
            artworkUrl = runCatching { extractor.thumbnailUrl }.getOrNull()?.takeIf { it.isNotBlank() },
            items = items,
            declaredCount = declared?.toInt(),
            complete = declared != null && items.size >= declared && items.size < maxItems,
        )
    }

    /** The video id is the last path segment of the watch url. */
    private fun videoIdOf(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            .takeIf { ID_PATTERN.matches(it) }
            .orEmpty()

    private fun extractorFor(id: String): PlaylistExtractor {
        val extractor = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$id")
        extractor.fetchPage()
        return extractor
    }

    companion object {
        /** YouTube playlist ids are eleven characters. */
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

        private val YOUTUBE_HOSTS = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtube-nocookie.com",
        )
    }
}
