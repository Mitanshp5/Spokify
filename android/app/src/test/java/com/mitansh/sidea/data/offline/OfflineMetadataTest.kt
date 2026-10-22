package com.mitansh.sidea.data.offline

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMetadataTest {

    private val track = Track(
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        durationMs = 213_000L,
        artworkUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
    )

    @Test
    fun roundTripsEveryField() {
        val original = OfflineMetadata.of(track, automatic = true)
        val decoded = OfflineMetadata.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals("youtube", decoded!!.source)
        assertEquals(track.title, decoded.title)
        assertEquals(track.artist, decoded.artist)
        assertEquals(track.durationMs, decoded.durationMs)
        assertEquals(track.artworkUrl, decoded.artworkUrl)
        assertTrue(decoded.automatic)
        assertEquals(track, decoded.toTrack(track.id))
    }

    @Test
    fun roundTripsManualEntryWithoutArtwork() {
        val manual = OfflineMetadata.of(track.copy(artworkUrl = null), automatic = false)
        val decoded = OfflineMetadata.decode(manual.encode())

        assertNull(decoded!!.artworkUrl)
        assertFalse(decoded.automatic)
    }

    @Test
    fun decodesTitlesWithSeparatorsAndUnicode() {
        val awkward = track.copy(title = "a\u0000b|c\n4:59 — ünïcode", artist = "")
        val decoded = OfflineMetadata.decode(OfflineMetadata.of(awkward, automatic = false).encode())

        assertEquals(awkward.title, decoded!!.title)
        assertEquals("", decoded.artist)
    }

    @Test
    fun returnsNullForMissingOrCorruptPayload() {
        assertNull(OfflineMetadata.decode(null))
        assertNull(OfflineMetadata.decode(ByteArray(0)))
        assertNull(OfflineMetadata.decode(byteArrayOf(1, 2, 3, 4, 5)))
    }
}
