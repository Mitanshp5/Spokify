package com.mitansh.sidea.core

/**
 * Reads the upstream expiry stamp out of a media URL.
 *
 * Google Video URLs carry the expiry either as an `expire=` query parameter or as an
 * `/expire/<seconds>` path segment. Both are Unix seconds.
 */
object StreamUrlExpiry {

    private val QUERY = Regex("[?&]expire=(\\d+)")
    private val PATH = Regex("/expire/(\\d+)")

    fun expiryEpochMs(url: String): Long? {
        val seconds = QUERY.find(url)?.groupValues?.get(1)
            ?: PATH.find(url)?.groupValues?.get(1)
            ?: return null
        return seconds.toLongOrNull()?.times(1_000L)
    }
}
