package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per track that has actually been played, keyed by the durable
 * [com.mitansh.sidea.core.Track.uid].
 *
 * The row stores enough metadata to render the library without re-resolving the source, and the
 * timestamp drives both the "recently played" order and bounded eviction.
 */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val uid: String,
    val trackId: String,
    val source: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val lastPlayedAtEpochMs: Long,
    val playCount: Int,
)
