package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey val id: String, val name: String, val createdAtEpochMs: Long, val position: Int)
