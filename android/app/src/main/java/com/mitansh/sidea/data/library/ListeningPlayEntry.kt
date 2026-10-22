package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One qualified playback session, recorded by the existing real-playback threshold. */
@Entity(tableName = "listening_plays", indices = [Index("uid", "playedAtEpochMs"), Index("playedAtEpochMs")])
data class ListeningPlayEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val playedAtEpochMs: Long,
)

data class RecentPlayCount(val uid: String, val playCount: Int, val lastPlayedAtEpochMs: Long)
