package com.mitansh.sidea.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches resolved, directly playable audio URLs per track.
 *
 * Resolving a track costs two sequential upstream round-trips, and Media3 reopens the data source
 * on every seek and for every track it prepares ahead. Without a cache, each of those openings pays
 * the full resolution cost again, which shows up as long gaps before audio and as stalls when the
 * upstream call is slow. Entries live only in memory because the URLs are credentials-bound and
 * expire; an entry inside its safety margin is treated as stale because Media3 may still be
 * buffering when the upstream expiry passes.
 *
 * This replaces an earlier cache that stored a resolution shape the resolver never produced, and
 * that was never actually wired into the playback path.
 */
class AudioResolutionCache(
    private val fallbackTtlMs: Long = DEFAULT_FALLBACK_TTL_MS,
    private val safetyMarginMs: Long = DEFAULT_SAFETY_MARGIN_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private class Entry(val audio: ResolvedAudio, val staleAtEpochMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    val size: Int get() = entries.size

    /** Returns a still-valid resolution for [key], or null when it must be resolved again. */
    fun get(key: String, nowMs: Long): ResolvedAudio? {
        val entry = entries[key] ?: return null
        if (nowMs >= entry.staleAtEpochMs) {
            entries.remove(key)
            return null
        }
        return entry.audio
    }

    fun put(key: String, audio: ResolvedAudio, nowMs: Long) {
        val staleAt = audio.expiresAtEpochMs?.minus(safetyMarginMs) ?: (nowMs + fallbackTtlMs)
        if (staleAt <= nowMs) return
        if (entries.size >= maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.staleAtEpochMs }
            oldest?.let { entries.remove(it.key, it.value) }
        }
        entries[key] = Entry(audio, staleAt)
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        /** Used when a URL exposes no expiry; short enough that a stale URL is rarely reused. */
        const val DEFAULT_FALLBACK_TTL_MS = 30L * 60L * 1_000L

        const val DEFAULT_SAFETY_MARGIN_MS = 60L * 1_000L

        /** Bounds how many tracks stay resolvable without a new upstream call. */
        const val DEFAULT_MAX_ENTRIES = 40
    }
}
