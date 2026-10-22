package com.mitansh.sidea.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResolutionCacheTest {

    private val now = 1_000_000L

    @Test
    fun `returns a resolution that is still valid`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 600_000), now)

        assertEquals("https://cdn/a", cache.get("a", now + 1_000)?.url)
    }

    @Test
    fun `treats a resolution expiring inside the safety margin as stale`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 30_000), now)

        assertNull(cache.get("a", now + 1_000))
    }

    @Test
    fun `evicts an entry once its expiry has passed`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 120_000), now)

        assertNull(cache.get("a", now + 120_001))
        assertEquals(0, cache.size)
    }

    @Test
    fun `uses the fallback lifetime when the url has no expiry`() {
        val cache = AudioResolutionCache(fallbackTtlMs = 300_000)
        cache.put("a", resolved(expiresAt = null), now)

        assertEquals("https://cdn/a", cache.get("a", now + 299_000)?.url)
        assertNull(cache.get("a", now + 300_001))
    }

    @Test
    fun `a second open of the same track reuses the resolution`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 600_000), now)

        assertEquals("https://cdn/a", cache.get("a", now)?.url)
        assertEquals(1, cache.size)
    }

    @Test
    fun `replaces a previous resolution for the same key`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 600_000), now)
        cache.put("a", resolved(url = "https://cdn/a-fresh", expiresAt = now + 600_000), now)

        assertEquals(1, cache.size)
        assertEquals("https://cdn/a-fresh", cache.get("a", now)?.url)
    }

    @Test
    fun `invalidating removes the entry so the next open resolves again`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now + 600_000), now)

        cache.invalidate("a")

        assertNull(cache.get("a", now))
    }

    @Test
    fun `the entry count stays bounded`() {
        val cache = AudioResolutionCache(maxEntries = 3)
        repeat(20) { index -> cache.put("track$index", resolved(expiresAt = now + 600_000), now) }

        assertTrue("expected bounded entry count, got ${cache.size}", cache.size <= 3)
    }

    @Test
    fun `an already stale resolution is not stored`() {
        val cache = AudioResolutionCache()
        cache.put("a", resolved(expiresAt = now - 1), now)

        assertNull(cache.get("a", now))
        assertEquals(0, cache.size)
    }

    private fun resolved(url: String = "https://cdn/a", expiresAt: Long?) = ResolvedAudio(
        url = url,
        mimeType = "audio/mp4",
        bitrateBps = 128_000,
        expiresAtEpochMs = expiresAt,
    )
}
