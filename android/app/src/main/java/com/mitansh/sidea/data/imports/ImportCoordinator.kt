package com.mitansh.sidea.data.imports

import android.content.Context
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.ImportProvenanceEntry
import com.mitansh.sidea.data.library.ImportProvenanceDao
import com.mitansh.sidea.data.library.PlaylistRepository
import com.mitansh.sidea.data.spotify.SpotifyRepository
import com.mitansh.sidea.data.spotify.matchSpotifyPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The one import flow, for every source.
 *
 * Keeps source identity and the review session alongside the saved playlist. Repeating a completed
 * commit returns the existing playlist.
 */
class ImportCoordinator(
    private val playlists: PlaylistRepository,
    private val provenance: ImportProvenanceDao,
    private val sessions: ImportSessionStore,
    private val spotify: SpotifyRepository = SpotifyRepository(),
    private val youtube: YouTubePlaylistReader = YouTubePlaylistReader(),
    private val search: suspend (String) -> List<Track>,
) {
    constructor(
        context: Context,
        playlists: PlaylistRepository,
        provenance: ImportProvenanceDao,
        spotify: SpotifyRepository = SpotifyRepository(),
        youtube: YouTubePlaylistReader = YouTubePlaylistReader(),
        sessions: ImportSessionStore = ImportSessionStore(context),
        search: suspend (String) -> List<Track>,
    ) : this(playlists, provenance, sessions, spotify, youtube, search)

    private val commitMutex = Mutex()

    /** What happened while reading a playlist. */
    data class ReadResult(
        val session: ImportSessionStore.Session,
        val error: String? = null,
    )

    /** What happened while committing an import. */
    data class CommitResult(
        val playlistId: String?,
        val saved: Int,
        val unmatched: Int,
        val alreadyCommitted: Boolean,
        val error: String? = null,
    )

    /**
     * Reads a playlist from a link and matches it to playable songs.
     *
     * The source is decided by the link, so the user does not have to pick. A YouTube link is read
     * with the playlist reader; a Spotify link is read with the Spotify reader.
     */
    suspend fun read(link: String, onProgress: suspend (String) -> Unit = {}): ReadResult = withContext(Dispatchers.IO) {
        try {
            val youtubeId = youtube.parseId(link)
            if (youtubeId != null) {
                return@withContext readYoutube(youtubeId)
            }
            val spotifyId = com.mitansh.sidea.data.spotify.SpotifyPlaylist.parseId(link)
            if (spotifyId != null) {
                return@withContext readSpotify(spotifyId, onProgress)
            }
            ReadResult(emptySession(), "That link is not a Spotify or YouTube playlist")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReadResult(emptySession(), e.message ?: "The playlist could not be read. Please try again.")
        }
    }

    private suspend fun readYoutube(id: String): ReadResult {
        val playlist = youtube.read(id)
        val items = playlist.items.map { item ->
            val track = Track(
                id = item.trackId,
                title = item.title,
                artist = item.artist,
                durationMs = item.durationMs,
                artworkUrl = item.artworkUrl,
            )
            ImportedItem(
                position = item.position,
                sourceTrackId = item.trackId,
                title = item.title,
                artist = item.artist,
                durationMs = item.durationMs,
                selected = track,
                status = ImportedItem.Status.MATCHED,
            )
        }
        val session = ImportSessionStore.Session(
            id = UUID.randomUUID().toString(),
            source = ImportedPlaylist.Source.YOUTUBE,
            sourceId = playlist.id,
            title = playlist.title.ifBlank { "YouTube playlist" },
            author = playlist.author,
            artworkUrl = playlist.artworkUrl,
            declaredCount = playlist.declaredCount,
            complete = playlist.complete,
            items = items,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        sessions.save(session)
        return ReadResult(session)
    }

    private suspend fun readSpotify(id: String, onProgress: suspend (String) -> Unit): ReadResult {
        val preview = spotify.publicPlaylist(id)
        if (preview.tracks.isEmpty()) {
            return ReadResult(emptySession(), "No songs were available in this playlist's public preview.")
        }
        val matched = matchSpotifyPlaylist(preview, onProgress = { current, total ->
            onProgress("Matching song $current of $total…")
        }) { query -> search(query) }
        val items = matched.matches.map { match ->
            ImportedItem(
                position = match.source.position,
                sourceTrackId = match.source.sourceId,
                title = match.source.title,
                artist = match.source.artist,
                durationMs = match.source.durationMs,
                selected = match.selected,
                alternatives = match.alternatives,
                status = when (match.status) {
                    com.mitansh.sidea.data.spotify.SpotifyMatchStatus.AUTO -> ImportedItem.Status.MATCHED
                    com.mitansh.sidea.data.spotify.SpotifyMatchStatus.REVIEW -> ImportedItem.Status.REVIEW
                    com.mitansh.sidea.data.spotify.SpotifyMatchStatus.MISSING -> ImportedItem.Status.MISSING
                },
            )
        }
        val session = ImportSessionStore.Session(
            id = UUID.randomUUID().toString(),
            source = ImportedPlaylist.Source.SPOTIFY,
            sourceId = preview.id,
            title = preview.title.ifBlank { "Spotify playlist" },
            author = "",
            artworkUrl = null,
            declaredCount = preview.declaredCount,
            complete = preview.completeness == com.mitansh.sidea.data.spotify.SpotifyCompleteness.COMPLETE,
            items = items,
            updatedAtEpochMs = System.currentTimeMillis(),
            warning = listOfNotNull(preview.warning, "${matched.failedSearches} song searches failed. Read the playlist again to retry them."
                .takeIf { matched.failedSearches > 0 }).joinToString(" ").ifBlank { null },
        )
        sessions.save(session)
        return ReadResult(session)
    }

    /**
     * Saves the reviewed songs as a local playlist.
     *
     * Committing the same session twice does not create a second playlist: the session remembers
     * which playlist it produced, and the provenance rows are replaced rather than duplicated.
     */
    suspend fun commit(sessionId: String, selections: Map<Int, Track> = emptyMap()): CommitResult = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            val stored = sessions.load(sessionId)
                ?: return@withLock CommitResult(null, 0, 0, false, "The import is no longer available")
            if (selections.keys.any { position -> stored.items.none { it.position == position } }) {
                return@withLock CommitResult(null, 0, 0, false, "A selected song is no longer available. Read the playlist again.")
            }
            val session = stored.copy(items = stored.items.map { item ->
                val choice = selections[item.position] ?: return@map item
                val candidate = (item.alternatives + listOfNotNull(item.selected)).firstOrNull { it.uid == choice.uid }
                    ?: return@withLock CommitResult(null, 0, 0, false, "A selected song is no longer available. Read the playlist again.")
                item.copy(selected = candidate, status = ImportedItem.Status.MATCHED)
            })
            if (session.committedPlaylistId != null) {
                return@withLock CommitResult(session.committedPlaylistId, stored.items.count { it.selected != null }, stored.items.count { it.selected == null }, true)
            }
            val tracks = session.items.mapNotNull { it.selected }
            if (tracks.isEmpty()) {
                return@withLock CommitResult(null, 0, session.items.size, false, "Choose at least one matching song before saving.")
            }
            val playlistId = playlists.create(session.title.ifBlank { "Imported playlist" }, tracks)
            provenance.deleteForPlaylist(playlistId)
            provenance.insertAll(
                session.items.mapNotNull { item ->
                    val selected = item.selected ?: return@mapNotNull null
                    ImportProvenanceEntry(
                        entryId = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        source = session.source.name,
                        sourceTrackId = item.sourceTrackId ?: selected.id,
                        sourcePosition = item.position,
                        chosenTrackId = selected.id,
                        sourceTitle = item.title,
                        sourceArtist = item.artist,
                        importedAtEpochMs = System.currentTimeMillis(),
                    )
                }
            )
            val committed = session.copy(committedPlaylistId = playlistId, updatedAtEpochMs = System.currentTimeMillis())
            sessions.save(committed)
            val unmatched = session.items.count { it.selected == null }
            CommitResult(playlistId, tracks.size, unmatched, false)
        }
    }

    /** The import sessions that have not been saved yet. */
    fun pendingSessions(): List<ImportSessionStore.Session> = sessions.pending()

    /** The source identity of every song in a saved playlist. */
    suspend fun provenanceFor(playlistId: String): List<ImportProvenanceEntry> =
        provenance.forPlaylist(playlistId)

    private fun emptySession() = ImportSessionStore.Session(
        id = "",
        source = ImportedPlaylist.Source.SPOTIFY,
        sourceId = "",
        title = "",
        author = "",
        artworkUrl = null,
        declaredCount = null,
        complete = false,
        items = emptyList(),
    )
}
