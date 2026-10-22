package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlExpiryTest {

    @Test
    fun `reads the expire query parameter as epoch milliseconds`() {
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?itag=140&expire=1790000000&mime=audio%2Fmp4"

        assertEquals(1_790_000_000_000L, StreamUrlExpiry.expiryEpochMs(url))
    }

    @Test
    fun `reads an expire path segment`() {
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback/expire/1790000123/ip/1.2.3.4"

        assertEquals(1_790_000_123_000L, StreamUrlExpiry.expiryEpochMs(url))
    }

    @Test
    fun `returns null when the url carries no expiry`() {
        assertNull(StreamUrlExpiry.expiryEpochMs("https://example.com/audio.mp4"))
    }

    @Test
    fun `returns null when the expire value is not a number`() {
        assertNull(StreamUrlExpiry.expiryEpochMs("https://rr1---sn-x.googlevideo.com/videoplayback?expire=soon"))
    }
}
