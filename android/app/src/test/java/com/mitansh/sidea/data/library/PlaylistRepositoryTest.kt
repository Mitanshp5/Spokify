package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRepositoryTest {
    @Test fun createAndAppendPreservesRepeatedTracks() = runBlocking {
        val dao = MemoryPlaylistDao()
        val repository = PlaylistRepository(dao)
        val track = Track("x", "title", "artist")
        val id = repository.create("mix", listOf(track, track))
        repository.append(id, track)
        assertEquals(listOf("youtube:x", "youtube:x", "youtube:x"), repository.observeEntries(id).first().map { it.track.uid })
    }

    @Test fun renameRemoveMoveAndDeleteAreScoped() = runBlocking {
        val dao = MemoryPlaylistDao()
        val repository = PlaylistRepository(dao)
        val track = Track("x", "title", "artist")
        val one = repository.create("one", listOf(track, Track("y", "other", "artist")))
        val two = repository.create("two", listOf(track))
        repository.rename(one, "renamed")
        val before = repository.observeEntries(one).first()
        repository.moveEntry(one, before[1].entryId, 0)
        assertEquals(listOf("youtube:y", "youtube:x"), repository.observeEntries(one).first().map { it.track.uid })
        repository.removeEntry(before[0].entryId)
        assertEquals(listOf("youtube:y"), repository.observeEntries(one).first().map { it.track.uid })
        repository.deletePlaylist(one)
        assertEquals(listOf("two"), repository.observePlaylists().first().map { it.name })
        assertEquals(emptyList<PlaylistTrackEntry>(), repository.observeEntries(one).first())
        assertEquals(1, repository.observeEntries(two).first().size)
    }

    @Test fun rejectsBlankPlaylistNames() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        try { repository.create("  "); error("blank name accepted") } catch (_: IllegalArgumentException) { }
        val id = repository.create("valid")
        try { repository.rename(id, "\t"); error("blank name accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun savePickerAddsToMultiplePlaylistsWithoutDuplicatingOnRetry() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val song = Track("same", "Song", "Artist")
        val one = repository.create("one")
        val two = repository.create("two")
        repeat(2) { repository.updateMemberships(song, emptySet(), setOf(one, two)) }
        assertEquals(1, repository.observeEntries(one).first().size)
        assertEquals(1, repository.observeEntries(two).first().size)
        assertEquals(setOf(one, two), repository.observeMemberships().first().map { it.playlistId }.toSet())
    }

    @Test fun deselectingRemovesAllCopiesOnlyFromThatSourceAndPlaylist() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val song = Track("same", "Song", "Artist")
        val otherSource = song.copy(source = "other")
        val one = repository.create("one", listOf(song, otherSource, song))
        val two = repository.create("two", listOf(song))
        repository.updateMemberships(song, setOf(one, two), setOf(two))
        assertEquals(listOf(otherSource), repository.observeEntries(one).first().map { it.track })
        assertEquals(listOf(song), repository.observeEntries(two).first().map { it.track })
    }

    @Test fun unchangedDestinationsPreserveRepeatedEntriesAndUnrelatedEdits() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val song = Track("same", "Song", "Artist")
        val one = repository.create("one", listOf(song, song))
        val two = repository.create("two")
        val initial = setOf(one)
        // Another surface adds this song after the picker opens. It must stay untouched.
        repository.append(two, song)
        val before = repository.observeEntries(one).first()
        repository.updateMemberships(song, initial, initial)
        assertEquals(before, repository.observeEntries(one).first())
        assertEquals(listOf(song), repository.observeEntries(two).first().map { it.track })
    }

    @Test fun destinationDeletedWhilePickerIsOpenIsNotRecreated() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val id = repository.create("deleted")
        repository.deletePlaylist(id)
        repository.updateMemberships(Track("song", "Song", "Artist"), emptySet(), setOf(id))
        assertEquals(emptyList<PlaylistTrackEntry>(), repository.observeEntries(id).first())
        assertEquals(emptyList<PlaylistMembership>(), repository.observeMemberships().first())
    }

    @Test fun removingThenSavingKeepsExistingOrderAndAddsAtTheEnd() = runBlocking {
        val repository = PlaylistRepository(MemoryPlaylistDao())
        val song = Track("x", "Song", "Artist")
        val other = Track("y", "Other", "Artist")
        val id = repository.create("mix", listOf(song, other))
        repository.updateMemberships(song, setOf(id), emptySet())
        repository.updateMemberships(song, emptySet(), setOf(id))
        assertEquals(listOf(other, song), repository.observeEntries(id).first().map { it.track })
    }
}

internal class MemoryPlaylistDao : PlaylistDao() {
    private val playlists = mutableListOf<Playlist>()
    private val entries = mutableListOf<PlaylistTrackEntry>()
    private val playlistState = MutableStateFlow<List<Playlist>>(emptyList())
    private val entryState = MutableStateFlow<List<PlaylistTrackEntry>>(emptyList())
    private fun publish() { playlistState.value = playlists.toList(); entryState.value = entries.toList() }
    override fun observePlaylists(): Flow<List<Playlist>> = playlistState
    override fun observeEntries(playlistId: String): Flow<List<PlaylistTrackEntry>> = entryState.map { rows -> rows.filter { it.playlistId == playlistId }.sortedBy { it.position } }
    override fun observeMemberships(): Flow<List<PlaylistMembership>> = entryState.map { rows -> rows.map { PlaylistMembership(it.playlistId, it.track.uid) }.distinct() }
    override suspend fun playlistExists(playlistId: String) = playlists.any { it.id == playlistId }
    override suspend fun removeTrack(playlistId: String, source: String, trackId: String) { entries.removeAll { it.playlistId == playlistId && it.source == source && it.trackId == trackId }; publish() }
    override fun observeEntryCounts(): Flow<List<PlaylistSongCount>> = entryState.map { rows ->
        rows.groupingBy { it.playlistId }.eachCount().map { (id, count) -> PlaylistSongCount(id, count) }
    }
    override suspend fun insertPlaylist(playlist: Playlist) { playlists += playlist; publish() }
    override suspend fun insertEntries(entries: List<PlaylistTrackEntry>) { this.entries += entries; publish() }
    override suspend fun nextPlaylistPosition() = playlists.size
    override suspend fun nextEntryPosition(playlistId: String) = (entries.filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1) + 1
    override suspend fun rename(playlistId: String, name: String) { playlists.indices.firstOrNull { playlists[it].id == playlistId }?.let { playlists[it] = playlists[it].copy(name = name) }; publish() }
    override suspend fun removeEntry(entryId: String) { entries.removeAll { it.entryId == entryId }; publish() }
    override suspend fun clearEntries(playlistId: String) { entries.removeAll { it.playlistId == playlistId }; publish() }
    override suspend fun setPosition(entryId: String, position: Int) { entries.indices.firstOrNull { entries[it].entryId == entryId }?.let { entries[it] = entries[it].copy(position = position) }; publish() }
    override suspend fun entries(playlistId: String) = entries.filter { it.playlistId == playlistId }.sortedBy { it.position }
    override suspend fun deleteRow(playlistId: String) { playlists.removeAll { it.id == playlistId }; publish() }
    override suspend fun deletePlaylist(playlistId: String) { clearEntries(playlistId); deleteRow(playlistId) }
}
