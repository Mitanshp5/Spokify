package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A track the user explicitly saved, keyed by the durable [com.mitansh.sidea.core.Track.uid]. */
@Entity(tableName = "favorites")
data class FavoriteEntry(
    @PrimaryKey val uid: String,
    val trackId: String,
    val source: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val savedAtEpochMs: Long,
)
