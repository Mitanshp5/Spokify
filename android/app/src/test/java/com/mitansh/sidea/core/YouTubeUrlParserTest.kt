package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {

    @Test
    fun `extracts video id from standard watch url`() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `extracts video id from short share url`() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.videoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `extracts video id from music watch url with extra params`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeUrlParser.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"),
        )
    }

    @Test
    fun `returns null for a url without a video id`() {
        assertNull(YouTubeUrlParser.videoId("https://www.youtube.com/channel/UCabc"))
    }

    @Test
    fun `returns null for malformed input`() {
        assertNull(YouTubeUrlParser.videoId("not a url"))
    }

    @Test
    fun `extracts video id from shorts and embed paths`() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.videoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.videoId("https://www.youtube.com/live/dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects an id that is not eleven URL-safe characters`() {
        assertNull(YouTubeUrlParser.videoId("https://www.youtube.com/watch?v=tooshort"))
        assertNull(YouTubeUrlParser.videoId("https://youtu.be/abcdefghijkl"))
    }
}
