package com.mitansh.sidea.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ListeningStatsDao {

    @Query("SELECT * FROM listening_stats WHERE uid = :uid")
    abstract suspend fun find(uid: String): ListeningStatEntry?

    @Query("SELECT * FROM listening_stats ORDER BY playCount DESC")
    abstract suspend fun all(): List<ListeningStatEntry>

    @Query("SELECT * FROM listening_stats ORDER BY playCount DESC")
    abstract fun observeAll(): Flow<List<ListeningStatEntry>>

    @Query("SELECT * FROM listening_stats WHERE playCount >= :minPlays ORDER BY lastPlayedAtEpochMs DESC")
    abstract suspend fun playedAtLeast(minPlays: Int): List<ListeningStatEntry>

    @Query("SELECT COUNT(*) FROM listening_stats")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entry: ListeningStatEntry)

    @Query("DELETE FROM listening_stats")
    protected abstract suspend fun clearStats()

    @Query("DELETE FROM listening_plays")
    protected abstract suspend fun clearPlays()

    @Insert
    protected abstract suspend fun insertPlay(entry: ListeningPlayEntry)

    @Query("DELETE FROM listening_plays WHERE playedAtEpochMs < :cutoff")
    protected abstract suspend fun trimPlays(cutoff: Long)

    @Query("SELECT uid, COUNT(*) AS playCount, MAX(playedAtEpochMs) AS lastPlayedAtEpochMs FROM listening_plays WHERE playedAtEpochMs >= :cutoff AND playedAtEpochMs <= :now GROUP BY uid")
    abstract fun observeRecentPlays(cutoff: Long, now: Long): Flow<List<RecentPlayCount>>

    @Transaction
    open suspend fun recordQualified(metadata: ListeningStatEntry, completed: Boolean): Int {
        val previous = find(metadata.uid)
        val row = metadata.copy(
            playCount = (previous?.playCount ?: 0) + 1,
            completedCount = (previous?.completedCount ?: 0) + if (completed) 1 else 0,
            skipCount = previous?.skipCount ?: 0,
            firstPlayedAtEpochMs = previous?.firstPlayedAtEpochMs ?: metadata.lastPlayedAtEpochMs,
        )
        upsert(row)
        insertPlay(ListeningPlayEntry(uid = row.uid, playedAtEpochMs = row.lastPlayedAtEpochMs))
        // Keep a small buffer beyond the playlist's rolling window.
        trimPlays(row.lastPlayedAtEpochMs - OnRepeatPolicy.WINDOW_MS - 5L * 24 * 60 * 60 * 1000)
        return row.playCount
    }

    /** Completion/skip bookkeeping must not overwrite a concurrently qualified replay. */
    @Transaction
    open suspend fun recordOutcome(metadata: ListeningStatEntry, completed: Boolean) {
        val previous = find(metadata.uid)
        upsert(metadata.copy(
            durationMs = metadata.durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0,
            artworkUrl = metadata.artworkUrl ?: previous?.artworkUrl,
            playCount = previous?.playCount ?: 0,
            completedCount = (previous?.completedCount ?: 0) + if (completed) 1 else 0,
            skipCount = (previous?.skipCount ?: 0) + if (completed) 0 else 1,
            firstPlayedAtEpochMs = previous?.firstPlayedAtEpochMs ?: metadata.firstPlayedAtEpochMs,
            lastPlayedAtEpochMs = previous?.lastPlayedAtEpochMs ?: metadata.lastPlayedAtEpochMs,
        ))
    }

    @Transaction
    open suspend fun clear() { clearStats(); clearPlays() }
}
