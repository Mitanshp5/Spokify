package com.mitansh.sidea.data.imports

import com.mitansh.sidea.core.Track

/**
 * A playlist read from any source, in the shape the import flow works with.
 *
 * Both sources produce this, so the review screen, the saving and the provenance work are the same
 * for a Spotify playlist and a YouTube playlist.
 */
data class ImportedPlaylist(
    /** Which source it came from. */
    val source: Source,
    /** The source's own id, kept so the import can be traced back to it. */
    val sourceId: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    /** The songs, in the order the source had them. */
    val items: List<ImportedItem>,
    /** How many songs the source says there are, when it says. */
    val declaredCount: Int?,
    /** Whether the whole playlist was read. */
    val complete: Boolean,
) {
    enum class Source { SPOTIFY, YOUTUBE }
}

/**
 * One song in an imported playlist.
 *
 * The source identity and the playable recording are kept apart. A Spotify track id and a YouTube
 * video id are different things, and a matched recording is a choice that can be wrong, so both are
 * kept and the choice is recorded.
 */
data class ImportedItem(
    /** The position the song had in the source. */
    val position: Int,
    /** The source's own id for the song, when it has one. */
    val sourceTrackId: String?,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** The recording that was chosen to play, when one was chosen. */
    val selected: Track? = null,
    /** The other recordings that could have been chosen. */
    val alternatives: List<Track> = emptyList(),
    /** How the choice was made. */
    val status: Status = Status.MISSING,
) {
    enum class Status { MATCHED, REVIEW, MISSING }
}
