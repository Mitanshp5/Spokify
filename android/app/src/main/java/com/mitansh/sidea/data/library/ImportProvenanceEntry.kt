package com.mitansh.sidea.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One song in an import, kept with the identity it came from.
 *
 * The import itself is transient, but the provenance is not: when a playlist is saved, each song
 * keeps the id the source gave it, the position it had, and the recording that was chosen to play
 * it. Without this an imported song is indistinguishable from one the user added by hand, and the
 * original source cannot be shown or re-imported.
 */
@Entity(
    tableName = "import_provenance",
    indices = [Index("playlistId"), Index("sourceTrackId")],
)
data class ImportProvenanceEntry(
    @PrimaryKey val entryId: String,
    /** The local playlist the song was saved into. */
    val playlistId: String,
    /** Which source it came from. */
    val source: String,
    /** The source's own id for the song. */
    val sourceTrackId: String,
    /** The position the song had in the source playlist. */
    val sourcePosition: Int,
    /** The id of the recording that was chosen to play it. */
    val chosenTrackId: String,
    /** The title as the source spelled it, kept even if the recording differs. */
    val sourceTitle: String,
    val sourceArtist: String,
    /** When the song was saved. */
    val importedAtEpochMs: Long,
    /** The date the source gave, for a saved song. */
    val sourceSavedAt: String? = null,
)
