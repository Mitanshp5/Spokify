package com.mitansh.sidea.data.spotify

import org.junit.Assert.*
import org.junit.Test

class SpotifyArtworkTest {
    private fun artwork(track: SpotifySourceTrack): String? = track.artworkUrl

    @Test fun publicPageKeepsAlbumCoverWithoutInventingUnavailableArtwork() {
        val id = "0123456789ABCDEFGHIJKL"
        val body = """{"data":{"playlistV2":{"uri":"spotify:playlist:$id","content":{"totalCount":2,"pagingInfo":{"nextOffset":null},"items":[{"itemV2":{"data":{"name":"Song","albumOfTrack":{"coverArt":{"sources":[{"url":"https://i.scdn.co/image/actual-cover","width":300}]}}}}},{"itemV2":{"data":{}}}]}}}}"""
        val tracks = SpotifyPublicPages.parse(body, id, 0).tracks
        assertEquals("https://i.scdn.co/image/actual-cover", artwork(tracks[0]))
        assertNull(artwork(tracks[1]))
        assertEquals(listOf(0, 1), tracks.map { it.position })
    }

    @Test fun embedKeepsPerTrackImageRatherThanPlaylistCover() {
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"coverArt":{"sources":[{"url":"https://example.test/playlist"}]},"trackList":[{"title":"Song","albumArt":{"sources":[{"url":"https://i.scdn.co/image/embed-cover"}]}},{"title":"No cover"}]}}}}}}</script>"""
        val tracks = SpotifyPlaylist.parseEmbed(html).tracks
        assertEquals("https://i.scdn.co/image/embed-cover", artwork(tracks[0]))
        assertNull(artwork(tracks[1]))
    }
}
