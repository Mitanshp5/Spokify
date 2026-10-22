package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackTest {

    @Test
    fun `uid is qualified by source so different sources cannot collide`() {
        assertEquals("youtube:dQw4w9WgXcQ", track(id = "dQw4w9WgXcQ").uid)
        assertNotEquals(
            track(id = "dQw4w9WgXcQ").uid,
            track(id = "dQw4w9WgXcQ").copy(source = "local").uid,
        )
    }

    @Test
    fun `unknown duration and artwork are represented without inventing values`() {
        val t = track(id = "dQw4w9WgXcQ")
        assertEquals(0L, t.durationMs)
        assertEquals(null, t.artworkUrl)
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Title",
        artist = "Artist",
    )
}
