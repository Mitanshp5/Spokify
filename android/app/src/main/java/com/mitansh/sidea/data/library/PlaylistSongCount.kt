package com.mitansh.sidea.data.library

import androidx.room.ColumnInfo

/** How many songs a playlist holds, so a card can show its size without loading its entries. */
data class PlaylistSongCount(
    val playlistId: String,
    @ColumnInfo(name = "songCount") val songCount: Int,
    @ColumnInfo(name = "artworkUrl") val artworkUrl: String? = null,
)
