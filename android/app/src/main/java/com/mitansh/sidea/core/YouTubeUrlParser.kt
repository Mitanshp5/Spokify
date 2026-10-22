package com.mitansh.sidea.core

import java.net.URI

/**
 * Extracts a YouTube video id from a watch/share URL.
 *
 * Only the id is used downstream: the id is the stable source key that becomes part of a durable
 * [Track.uid], so a malformed id must be rejected rather than forwarded to the extractor.
 */
object YouTubeUrlParser {

    /** Real YouTube ids are exactly 11 URL-safe base64 characters. */
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    private val PATH_PREFIXES = listOf("/shorts/", "/embed/", "/live/")

    fun videoId(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null

        if (host == "youtu.be") {
            return uri.path?.trimStart('/')?.substringBefore('/')?.takeIf(VIDEO_ID::matches)
        }

        val path = uri.path.orEmpty()
        for (prefix in PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return path.removePrefix(prefix).substringBefore('/').takeIf(VIDEO_ID::matches)
            }
        }

        val query = uri.query ?: return null
        val value = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "v" }
            ?.get(1)
        return value?.takeIf(VIDEO_ID::matches)
    }
}
