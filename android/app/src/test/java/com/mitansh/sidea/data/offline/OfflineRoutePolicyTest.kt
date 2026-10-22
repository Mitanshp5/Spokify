package com.mitansh.sidea.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoutePolicyTest {

    @Test
    fun extractsTheStableTrackIdFromSideATrackUris() {
        assertEquals("dQw4w9WgXcQ", OfflineRoutePolicy.trackId("sidea-track://dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", OfflineRoutePolicy.trackId("sidea-track:/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", OfflineRoutePolicy.trackId("sidea-track:dQw4w9WgXcQ"))
    }

    @Test
    fun ignoresNonSideATrackUris() {
        assertNull(OfflineRoutePolicy.trackId("https://example.com/audio.m4a"))
        assertNull(OfflineRoutePolicy.trackId("sidea-track://"))
    }

    @Test
    fun routesOnlyCompletedIdsToCachedBytes() {
        val uri = "sidea-track://dQw4w9WgXcQ"
        assertTrue(OfflineRoutePolicy.routesToOffline(uri, setOf("dQw4w9WgXcQ")))
        assertFalse(OfflineRoutePolicy.routesToOffline(uri, setOf("other")))
        assertFalse(OfflineRoutePolicy.routesToOffline("https://example.com/a.m4a", setOf("dQw4w9WgXcQ")))
    }
}
