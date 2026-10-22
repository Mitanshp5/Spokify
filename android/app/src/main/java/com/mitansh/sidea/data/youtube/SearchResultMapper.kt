package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.core.YouTubeUrlParser
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Maps extractor search results to playable [Track]s, dropping anything this slice cannot play.
 *
 * Live streams are dropped because the resolver only accepts audio-only direct streams; the
 * extractor returns them mixed into song search results.
 */
object SearchResultMapper {

    fun toTracks(items: List<InfoItem>): List<Track> = items.mapNotNull(::toTrack)

    private fun toTrack(item: InfoItem): Track? {
        val stream = item as? StreamInfoItem ?: return null
        if (stream.streamType == StreamType.LIVE_STREAM) return null

        val videoId = YouTubeUrlParser.videoId(stream.url) ?: return null

        return Track(
            id = videoId,
            title = stream.name.orEmpty(),
            artist = stream.uploaderName.orEmpty(),
            durationMs = (stream.duration.coerceAtLeast(0L)) * 1_000L,
            artworkUrl = stream.thumbnailUrl,
        )
    }
}
