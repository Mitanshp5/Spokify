package com.mitansh.sidea.data.offline

/**
 * Decides whether a playback [androidx.media3.datasource.DataSpec] should be served from offline
 * bytes. This is pure string logic so the routing contract is unit tested without Android.
 */
object OfflineRoutePolicy {

    /** Scheme used for stable, source-qualified track URIs that carry no signed media URL. */
    const val SCHEME = "sidea-track"

    /** Extracts the track id from a `sidea-track` uri, or null for any other uri. */
    fun trackId(uri: String): String? {
        val prefix = "$SCHEME:"
        if (!uri.startsWith(prefix)) return null
        val id = uri.substring(prefix.length).trimStart('/').substringBefore('/').substringBefore('?')
        return id.ifBlank { null }
    }

    /** Routes to cached bytes only for a completed offline item; everything else streams. */
    fun routesToOffline(uri: String, completedIds: Set<String>): Boolean =
        trackId(uri)?.let { it in completedIds } == true
}
