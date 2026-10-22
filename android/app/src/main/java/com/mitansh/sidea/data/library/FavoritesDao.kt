package com.mitansh.sidea.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {

    @Query("SELECT * FROM favorites ORDER BY savedAtEpochMs DESC")
    fun observe(): Flow<List<FavoriteEntry>>

    @Query("SELECT * FROM favorites WHERE uid = :uid")
    suspend fun find(uid: String): FavoriteEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FavoriteEntry)

    @Query("DELETE FROM favorites WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)
}
