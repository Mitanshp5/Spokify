package com.mitansh.sidea.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImportProvenanceDao {

    @Query("SELECT * FROM import_provenance WHERE playlistId = :playlistId ORDER BY sourcePosition ASC")
    suspend fun forPlaylist(playlistId: String): List<ImportProvenanceEntry>

    @Query("SELECT COUNT(*) FROM import_provenance WHERE playlistId = :playlistId")
    suspend fun countForPlaylist(playlistId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ImportProvenanceEntry>)

    @Query("DELETE FROM import_provenance WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)
}
