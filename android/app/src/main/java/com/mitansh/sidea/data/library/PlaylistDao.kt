package com.mitansh.sidea.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position, createdAtEpochMs") abstract fun observePlaylists(): Flow<List<Playlist>>
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position") abstract fun observeEntries(playlistId: String): Flow<List<PlaylistTrackEntry>>
    @Query("SELECT playlistId, COUNT(*) AS songCount, (SELECT artworkUrl FROM playlist_entries AS cover WHERE cover.playlistId = playlist_entries.playlistId AND artworkUrl IS NOT NULL ORDER BY position LIMIT 1) AS artworkUrl FROM playlist_entries GROUP BY playlistId")
    abstract fun observeEntryCounts(): Flow<List<PlaylistSongCount>>
    @Query("SELECT DISTINCT playlistId, source || ':' || trackId AS uid FROM playlist_entries")
    abstract fun observeMemberships(): Flow<List<PlaylistMembership>>
    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :playlistId)")
    protected abstract suspend fun playlistExists(playlistId: String): Boolean
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND source = :source AND trackId = :trackId")
    protected abstract suspend fun removeTrack(playlistId: String, source: String, trackId: String)
    @Insert protected abstract suspend fun insertPlaylist(playlist: Playlist)
    @Insert protected abstract suspend fun insertEntries(entries: List<PlaylistTrackEntry>)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlists") protected abstract suspend fun nextPlaylistPosition(): Int
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlistId = :playlistId") protected abstract suspend fun nextEntryPosition(playlistId: String): Int
    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId") abstract suspend fun rename(playlistId: String, name: String)
    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId") abstract suspend fun removeEntry(entryId: String)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId") protected abstract suspend fun clearEntries(playlistId: String)
    @Query("UPDATE playlist_entries SET position = :position WHERE entryId = :entryId") protected abstract suspend fun setPosition(entryId: String, position: Int)
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position") protected abstract suspend fun entries(playlistId: String): List<PlaylistTrackEntry>
    @Query("DELETE FROM playlists WHERE id = :playlistId") protected abstract suspend fun deleteRow(playlistId: String)

    @Transaction
    open suspend fun create(playlist: Playlist, tracks: List<PlaylistTrackEntry>) {
        insertPlaylist(playlist.copy(position = nextPlaylistPosition()))
        insertEntries(tracks)
    }

    @Transaction
    open suspend fun append(playlistId: String, entry: PlaylistTrackEntry) {
        insertEntries(listOf(entry.copy(position = nextEntryPosition(playlistId))))
    }

    /** Apply only edited destinations. Checking an existing song never duplicates it. */
    @Transaction
    open suspend fun updateMemberships(entry: PlaylistTrackEntry, initial: Set<String>, selected: Set<String>) {
        for (id in (initial - selected)) removeTrack(id, entry.source, entry.trackId)
        for (id in (selected - initial)) {
            if (playlistExists(id) && entries(id).none { it.source == entry.source && it.trackId == entry.trackId }) {
                append(id, entry.copy(entryId = java.util.UUID.randomUUID().toString(), playlistId = id))
            }
        }
    }

    @Transaction
    open suspend fun move(playlistId: String, entryId: String, position: Int) {
        val ordered = entries(playlistId).toMutableList()
        val moving = ordered.firstOrNull { it.entryId == entryId } ?: return
        ordered.remove(moving)
        ordered.add(position.coerceIn(0, ordered.size), moving)
        clearEntries(playlistId)
        insertEntries(ordered.mapIndexed { index, row -> row.copy(position = index) })
    }

    @Transaction
    open suspend fun deletePlaylist(playlistId: String) {
        clearEntries(playlistId)
        deleteRow(playlistId)
    }
}
