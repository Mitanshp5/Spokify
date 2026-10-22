package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.AudioResolutionCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks that a resolution is paid for once, not once per catalog.
 *
 * The playback service and the download manager each build their own catalog, and both resolve
 * the same songs. A per-instance cache would make each of them pay the two upstream round-trips
 * again for a song the other one had already resolved.
 */
class SharedResolutionCacheTest {

    @Test fun `failed track refresh invalidates shared playback and download cache without evicting other songs`() {
        val cache = AudioResolutionCache()
        val playerCatalog = YouTubeCatalog(cache)
        val downloadCatalog = YouTubeCatalog(cache)
        val now = System.currentTimeMillis()
        for (id in listOf("failed", "other")) {
            cache.put(id, com.mitansh.sidea.core.ResolvedAudio("https://example.test/$id", "audio/mp4", 128_000, now + 3_600_000), now)
        }
        playerCatalog.invalidate(com.mitansh.sidea.core.Track("failed", "Song", "Artist"))
        assertNull(downloadCatalog.resolutionsForTest.get("failed", now))
        assertNotNull(downloadCatalog.resolutionsForTest.get("other", now))
    }

    @Test
    fun `two catalogs share one resolution`() {
        val cache = AudioResolutionCache()
        val first = YouTubeCatalog(cache)
        val second = YouTubeCatalog(cache)

        assertEquals("the two catalogs should use the same cache", cache, first.resolutionsForTest)
        assertEquals(cache, second.resolutionsForTest)
    }

    @Test
    fun `the default catalog uses the shared cache`() {
        val first = YouTubeCatalog()
        val second = YouTubeCatalog()

        assertEquals(
            "two default catalogs should share one cache",
            first.resolutionsForTest,
            second.resolutionsForTest,
        )
    }

    @Test
    fun `a shared resolution is found by the other catalog`() {
        val cache = AudioResolutionCache()
        val first = YouTubeCatalog(cache)
        val second = YouTubeCatalog(cache)

        cache.put(
            "song1",
            com.mitansh.sidea.core.ResolvedAudio(
                "https://example.test/a",
                "audio/mp4",
                128_000,
                System.currentTimeMillis() + 3_600_000L,
            ),
            System.currentTimeMillis(),
        )

        assertNotNull("the second catalog should find the shared resolution", cache.get("song1", System.currentTimeMillis()))
        assertNull(cache.get("song2", System.currentTimeMillis()))
    }
}
