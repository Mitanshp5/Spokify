package com.mitansh.sidea.core

import java.net.URI

/** Request real album artwork at display resolution, retaining signed URLs verbatim. */
object ArtworkUrl {
    fun forDisplay(value: String?, size: Int = 1200): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        val host = uri.host?.lowercase().orEmpty()
        if (host != "lh3.googleusercontent.com" && host != "yt3.googleusercontent.com" &&
            host != "yt3.ggpht.com") return value
        if (uri.rawQuery != null || uri.rawFragment != null) return value
        val pixels = size.coerceIn(128, 1600)
        return value.replace(Regex("=w\\d+-h\\d+[^/?]*$"), "=w$pixels-h$pixels-l90-rj")
    }
}
