package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistRepository(private val dao: PlaylistDao) {
    fun observePlaylists(): Flow<List<Playlist>> = dao.observePlaylists()
    fun observeEntries(playlistId: String): Flow<List<PlaylistTrackEntry>> = dao.observeEntries(playlistId)
    fun observeMemberships(): Flow<List<PlaylistMembership>> = dao.observeMemberships()
    fun observeSummaries(): Flow<List<PlaylistSongCount>> = dao.observeEntryCounts()

    suspend fun updateMemberships(track: Track, initial: Set<String>, selected: Set<String>) =
        dao.updateMemberships(track.toPlaylistEntry("", 0), initial, selected)

    /** How many songs each playlist holds, so a card can show its size cheaply. */
    fun observeEntryCounts(): Flow<Map<String, Int>> =
        dao.observeEntryCounts().map { rows -> rows.associate { it.playlistId to it.songCount } }

    fun observeArtwork(): Flow<Map<String, String?>> =
        dao.observeEntryCounts().map { rows -> rows.associate { it.playlistId to it.artworkUrl } }

    suspend fun create(name: String, tracks: List<Track> = emptyList()): String {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        dao.create(Playlist(id, name, System.currentTimeMillis(), 0), tracks.mapIndexed { index, track -> track.toPlaylistEntry(id, index) })
        return id
    }

    suspend fun append(playlistId: String, track: Track) = dao.append(playlistId, track.toPlaylistEntry(playlistId, 0))
    suspend fun rename(playlistId: String, name: String) { require(name.isNotBlank()); dao.rename(playlistId, name) }
    suspend fun removeEntry(entryId: String) = dao.removeEntry(entryId)
    suspend fun moveEntry(playlistId: String, entryId: String, position: Int) = dao.move(playlistId, entryId, position)
    suspend fun deletePlaylist(playlistId: String) { dao.deletePlaylist(playlistId) }
}
