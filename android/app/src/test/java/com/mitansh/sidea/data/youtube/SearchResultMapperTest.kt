package com.mitansh.sidea.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Fixture tests for turning extractor search results into the app's Track model.
 *
 * Items are real extractor objects; no network call is made and no remote response is faked as live
 * evidence.
 */
class SearchResultMapperTest {

    @Test
    fun `maps a video result into a track with a durable id`() {
        val item = streamItem(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            name = "Never Gonna Give You Up",
            uploader = "Rick Astley",
            durationSeconds = 213,
            thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        )

        val tracks = SearchResultMapper.toTracks(listOf(item))

        assertEquals(1, tracks.size)
        assertEquals("dQw4w9WgXcQ", tracks[0].id)
        assertEquals("youtube:dQw4w9WgXcQ", tracks[0].uid)
        assertEquals("Never Gonna Give You Up", tracks[0].title)
        assertEquals("Rick Astley", tracks[0].artist)
        assertEquals(213_000L, tracks[0].durationMs)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", tracks[0].artworkUrl)
    }

    @Test
    fun `preserves the order the extractor returned`() {
        val tracks = SearchResultMapper.toTracks(
            listOf(
                streamItem(url = "https://www.youtube.com/watch?v=aaaaaaaaaaa"),
                streamItem(url = "https://www.youtube.com/watch?v=bbbbbbbbbbb"),
                streamItem(url = "https://www.youtube.com/watch?v=ccccccccccc"),
            ),
        )

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), tracks.map { it.id })
    }

    @Test
    fun `drops results that are not videos`() {
        val items = listOf<org.schabi.newpipe.extractor.InfoItem>(
            ChannelInfoItem(0, "https://www.youtube.com/channel/UCabc", "Some Channel"),
            streamItem(url = "https://www.youtube.com/watch?v=aaaaaaaaaaa"),
        )

        assertEquals(listOf("aaaaaaaaaaa"), SearchResultMapper.toTracks(items).map { it.id })
    }

    @Test
    fun `drops live streams that cannot be resolved as an audio-only song`() {
        val live = streamItem(url = "https://www.youtube.com/watch?v=lllllllllll", type = StreamType.LIVE_STREAM)

        assertEquals(emptyList<String>(), SearchResultMapper.toTracks(listOf(live)).map { it.id })
    }

    @Test
    fun `drops a video result whose url carries no video id`() {
        val malformed = streamItem(url = "https://www.youtube.com/watch")

        assertEquals(emptyList<String>(), SearchResultMapper.toTracks(listOf(malformed)).map { it.id })
    }

    @Test
    fun `an unknown duration is reported as zero rather than a negative value`() {
        val item = streamItem(url = "https://www.youtube.com/watch?v=aaaaaaaaaaa", durationSeconds = -1)

        assertEquals(0L, SearchResultMapper.toTracks(listOf(item))[0].durationMs)
    }

    private fun streamItem(
        url: String,
        name: String = "Title",
        uploader: String = "Uploader",
        durationSeconds: Long = 100,
        thumbnail: String = "https://i.ytimg.com/vi/x/hqdefault.jpg",
        type: StreamType = StreamType.VIDEO_STREAM,
    ): StreamInfoItem = StreamInfoItem(0, url, name, type).apply {
        uploaderName = uploader
        duration = durationSeconds
        thumbnailUrl = thumbnail
    }
}
