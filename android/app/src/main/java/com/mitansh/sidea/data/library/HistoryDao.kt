package com.mitansh.sidea.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    /** Full history, most recently played first. */
    @Query("SELECT * FROM history ORDER BY lastPlayedAtEpochMs DESC")
    suspend fun all(): List<HistoryEntry>

    @Query("SELECT * FROM history ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE uid = :uid")
    suspend fun find(uid: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE uid IN (:uids)")
    suspend fun deleteByUids(uids: List<String>)

    @Query("DELETE FROM history")
    suspend fun clear()
}
