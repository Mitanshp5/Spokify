package com.mitansh.sidea.core

/**
 * A resolved, directly playable audio URL.
 *
 * [expiresAtEpochMs] is the upstream expiry when the URL exposes one; such URLs are handed out only
 * from an in-memory cache because they are short-lived and must never be persisted.
 */
data class ResolvedAudio(
    val url: String,
    val mimeType: String?,
    val bitrateBps: Int,
    val expiresAtEpochMs: Long?,
)
