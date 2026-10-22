package com.mitansh.sidea.core

const val SOURCE_YOUTUBE = "youtube"

/**
 * A playable song, independent of where its bytes come from.
 *
 * [id] is the stable upstream id (for YouTube, the 11-character video id) and [uid] the durable
 * source-qualified key persisted in the local library and reused by later playlist features.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val source: String = SOURCE_YOUTUBE,
) {
    val uid: String get() = "$source:$id"
}
