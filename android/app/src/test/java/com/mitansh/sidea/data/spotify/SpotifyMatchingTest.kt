package com.mitansh.sidea.data.spotify

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMatchingTest {

    private fun source(title: String, artist: String, durationMs: Long = 200_000) =
        SpotifySourceTrack(0, title, artist, durationMs, "spotifyid")

    private fun candidate(title: String, artist: String, durationMs: Long = 200_000, id: String = "youtubeid") =
        Track(id, title, artist, durationMs)

    /** Builds a preview whose source rows carry their own position, as the real reader does. */
    private fun preview(vararg tracks: SpotifySourceTrack) = SpotifyPlaylistPreview(
        id = "playlistid",
        title = "Playlist",
        tracks = tracks.mapIndexed { index, track -> track.copy(position = index) },
        completeness = SpotifyCompleteness.COMPLETE,
        declaredCount = tracks.size,
    )

    @Test
    fun `an exact title and artist match is accepted`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk"))) {
            listOf(candidate("Get Lucky", "Daft Punk"))
        }

        assertEquals(SpotifyMatchStatus.AUTO, result.matches.single().status)
        assertNotNull(result.matches.single().selected)
    }

    @Test
    fun `a different artist is not accepted even with the same title`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk"))) {
            listOf(candidate("Get Lucky", "Some Cover Band"))
        }

        assertEquals(SpotifyMatchStatus.MISSING, result.matches.single().status)
        assertNull(result.matches.single().selected)
    }

    @Test
    fun `a live version is not accepted for a studio recording`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk"))) {
            listOf(candidate("Get Lucky (Live)", "Daft Punk"))
        }

        assertEquals(SpotifyMatchStatus.MISSING, result.matches.single().status)
    }

    @Test
    fun `a remix is not accepted for the original`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk"))) {
            listOf(candidate("Get Lucky (Remix)", "Daft Punk"))
        }

        assertTrue(result.matches.single().status != SpotifyMatchStatus.AUTO)
    }

    @Test
    fun `a long duration difference is not accepted`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk", 120_000))) {
            listOf(candidate("Get Lucky", "Daft Punk", 300_000))
        }

        assertTrue(result.matches.single().status != SpotifyMatchStatus.AUTO)
    }

    @Test
    fun `a close second candidate needs a review rather than an automatic match`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Get Lucky", "Daft Punk"))) {
            listOf(
                candidate("Get Lucky", "Daft Punk", id = "one"),
                candidate("Get Lucky", "Daft Punk & Pharrell Williams", id = "two"),
            )
        }

        assertEquals(SpotifyMatchStatus.REVIEW, result.matches.single().status)
        assertNull(result.matches.single().selected)
    }

    @Test
    fun `no candidates leaves the row missing`() = runBlocking {
        val result = matchSpotifyPlaylist(preview(source("Unknown Song", "Nobody"))) { emptyList() }

        assertEquals(SpotifyMatchStatus.MISSING, result.matches.single().status)
    }

    @Test
    fun `an explicit user selection is kept in its original position`() = runBlocking {
        val result = matchSpotifyPlaylist(
            preview(source("First", "A"), source("Second", "B")),
        ) {
            listOf(candidate("First", "A"))
        }
        val chosen = result.selectedTracks(mapOf(1 to candidate("Chosen", "B")))

        assertEquals(2, chosen.size)
        assertEquals("First", chosen[0]?.title)
        assertEquals("Chosen", chosen[1]?.title)
    }

    @Test
    fun `repeated source rows stay repeated`() = runBlocking {
        val result = matchSpotifyPlaylist(
            preview(source("Same", "A"), source("Same", "A")),
        ) {
            listOf(candidate("Same", "A"))
        }

        assertEquals(2, result.selectedTracks().size)
    }
}
