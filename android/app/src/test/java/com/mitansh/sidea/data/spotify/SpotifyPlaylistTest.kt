package com.mitansh.sidea.data.spotify

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.mitansh.sidea.core.Track

class SpotifyPlaylistTest {
    @Test fun publicReaderAcceptsTheIdExtractedByTheImportCoordinator() = runBlocking {
        val id = SpotifyPlaylist.parseId("https://open.spotify.com/playlist/37i9dQZF1EptFBKtaK7Wjn?si=8f9596f275c8480a")!!
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            assertEquals("https://open.spotify.com/embed/playlist/$id", url)
            SpotifyApiPage("""<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"On Repeat","trackList":[]}}}}}}</script>""")
        })
        assertEquals(id, repository.publicPlaylist(id).id)
    }

    @Test fun acceptsPlaylistLinksAndUriOnly() {
        assertEquals("0123456789ABCDEFGHIJKL", SpotifyPlaylist.parseId("spotify:playlist:0123456789ABCDEFGHIJKL"))
        assertEquals("0123456789ABCDEFGHIJKL", SpotifyPlaylist.parseId("https://open.spotify.com/intl-en/embed/playlist/0123456789ABCDEFGHIJKL?si=x"))
        listOf("0123456789ABCDEFGHIJKL", "https://evil.test/playlist/0123456789ABCDEFGHIJKL", "https://open.spotify.com/track/0123456789ABCDEFGHIJKL", "spotify:playlist:short", "spotify:playlist:bad:0123456789ABCDEFGHIJKL").forEach {
            assertNull(SpotifyPlaylist.parseId(it))
        }
    }

    @Test fun publicReaderRejectsInvalidInputsWithoutMakingRequests() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { _ -> error("Invalid input made a request") })
        listOf("short", "https://evil.test/playlist/37i9dQZF1EptFBKtaK7Wjn", "https://open.spotify.com/track/37i9dQZF1EptFBKtaK7Wjn", "../37i9dQZF1EptFBKtaK7Wjn").forEach { input ->
            try {
                repository.publicPlaylist(input)
                fail("Invalid input accepted")
            } catch (e: SpotifyImportException) {
                assertEquals(SpotifyFailure.INVALID_INPUT, e.kind)
            }
        }
    }

    @Test fun parserKeepsRepeatedAndUnavailableRowsAndNeverAssumesComplete() {
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix","trackList":[{"title":"Song","subtitle":"Artist","duration":120000,"uri":"spotify:track:0123456789ABCDEFGHIJKL"},{"title":"","subtitle":"","duration":0},{"title":"Song","subtitle":"Artist","duration":120000,"uri":"spotify:track:0123456789ABCDEFGHIJKL"}]}}}}}}</script>"""
        val result = SpotifyPlaylist.parseEmbed(html)
        assertEquals(3, result.tracks.size)
        assertEquals(listOf(0, 1, 2), result.tracks.map { it.position })
        assertEquals("Song", result.tracks[2].title)
        assertEquals(SpotifyCompleteness.UNKNOWN, result.completeness)
    }

    @Test fun liveRecordingIsNotAutomaticallyMatchedToStudioRecording() = runBlocking {
        val source = SpotifySourceTrack(0, "Blue Train (Live)", "John Coltrane", 600000, null)
        val result = matchSpotifyPlaylist(SpotifyPlaylistPreview("", "", listOf(source), SpotifyCompleteness.UNKNOWN)) {
            listOf(Track("1", "Blue Train", "John Coltrane", 600000), Track("2", "Blue Train (Live)", "John Coltrane", 600000))
        }
        assertEquals("Live version must rank ahead of studio", "2", result.matches.single().alternatives.first().id)
        assertNotEquals("version must not be erased by normalization", Track("1", "Blue Train", "John Coltrane", 600000), result.matches.single().selected)
        assertEquals(SpotifyMatchStatus.AUTO, result.matches.single().status)
        assertEquals("2", result.matches.single().selected?.id)
    }

    @Test fun publicImportUsesFixedEmbedEndpointAndReturnsParsedPreview() = runBlocking {
        val id = "0123456789ABCDEFGHIJKL"
        var requested = ""
        val html = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix","trackList":[{"title":"Song","subtitle":"Artist","duration":180000,"uri":"spotify:track:$id"}]}}}}}}</script>"""
        val repository = SpotifyRepository(SpotifyApiTransport { url -> requested = url; SpotifyApiPage(html) })
        val preview = repository.publicPlaylist("https://open.spotify.com/playlist/$id")
        assertEquals("https://open.spotify.com/embed/playlist/$id", requested)
        assertEquals(id, preview.id)
        val matched = matchSpotifyPlaylist(preview) { listOf(Track("yt1", "Song", "Artist", 180000)) }
        assertEquals("yt1", matched.selectedTracks().single()?.id)
    }

    @Test fun nearTieRequiresReviewAndRepeatedSourceRowsStaySelectableInOrder() = runBlocking {
        val row = SpotifySourceTrack(0, "Song", "Artist", 100000, null)
        val repeated = row.copy(position = 1)
        val preview = SpotifyPlaylistPreview("", "", listOf(row, repeated), SpotifyCompleteness.UNKNOWN)
        val candidateA = Track("a", "Song", "Artist", 100000)
        val candidateB = Track("b", "Song", "Artist", 100000)
        val result = matchSpotifyPlaylist(preview) { listOf(candidateA, candidateB) }
        assertEquals(SpotifyMatchStatus.REVIEW, result.matches[0].status)
        assertNull(result.matches[0].selected)
        assertEquals(listOf(null, null), result.selectedTracks())
        assertEquals(listOf("b", "b"), result.selectedTracks(mapOf(0 to candidateB, 1 to candidateB)).map { it?.id })
    }
}
