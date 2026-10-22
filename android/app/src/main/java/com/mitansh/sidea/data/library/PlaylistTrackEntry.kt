package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mitansh.sidea.core.Track

@Entity(tableName = "playlist_entries", indices = [Index("playlistId"), Index(value = ["playlistId", "position"], unique = true)])
data class PlaylistTrackEntry(
    @PrimaryKey val entryId: String, val playlistId: String, val position: Int,
    val trackId: String, val source: String, val title: String, val artist: String,
    val durationMs: Long, val artworkUrl: String?,
) {
    val track: Track get() = Track(trackId, title, artist, durationMs, artworkUrl, source)
}

fun Track.toPlaylistEntry(playlistId: String, position: Int) = PlaylistTrackEntry(
    java.util.UUID.randomUUID().toString(), playlistId, position, id, source, title, artist, durationMs, artworkUrl,
)
