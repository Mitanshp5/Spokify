package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable listening statistics for one track, kept separately from the recent-history list.
 *
 * The history table is a bounded "recently played" view whose rows are evicted, so a track's play
 * count would otherwise be lost once it falls out of that window. Radio ranking and
 * frequent-play saving both need counts that survive that trimming, so the same counts are kept
 * here without a row limit.
 *
 * [artistKey] is the normalized artist, so statistics for an artist can be grouped across the
 * different recordings of that artist.
 */
@Entity(
    tableName = "listening_stats",
    indices = [Index("playCount"), Index("artistKey")],
)
data class ListeningStatEntry(
    @PrimaryKey val uid: String,
    val artistKey: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val playCount: Int,
    val completedCount: Int,
    val skipCount: Int,
    val firstPlayedAtEpochMs: Long,
    val lastPlayedAtEpochMs: Long,
)
