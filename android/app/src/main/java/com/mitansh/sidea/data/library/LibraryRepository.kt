package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.history.HistoryPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The local library: durable listening history and favorites, both keyed by [Track.uid].
 *
 * History is bounded by [historyLimit] and only ever written from real playback (the caller decides
 * when playback has qualified, see [com.mitansh.sidea.data.history.HistoryPolicy]). Replaying a
 * track updates its row instead of duplicating it, so the same durable id can be reused by the
 * later playlist and radio slices.
 */
class LibraryRepository(
    private val historyDao: HistoryDao,
    private val favoritesDao: FavoritesDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyLimit: Int = HistoryPolicy.HISTORY_LIMIT,
) {

    fun observeHistory(): Flow<List<Track>> =
        historyDao.observe(historyLimit).map { rows -> rows.map(HistoryEntry::toTrack) }

    fun observeFavorites(): Flow<List<Track>> =
        favoritesDao.observe().map { rows -> rows.map(FavoriteEntry::toTrack) }

    suspend fun recordPlayed(track: Track) {
        val existing = historyDao.find(track.uid)
        historyDao.upsert(track.toHistoryEntry(clock(), (existing?.playCount ?: 0) + 1))
        evictBeyondLimit()
    }

    /** Persisted play count for a durable id, or zero when the track was never played. */
    suspend fun playCount(uid: String): Int = historyDao.find(uid)?.playCount ?: 0

    suspend fun isFavorite(uid: String): Boolean = favoritesDao.find(uid) != null

    /** Importing the same saved song again must never unlike an existing favorite. */
    suspend fun addFavorite(track: Track) {
        if (favoritesDao.find(track.uid) == null) favoritesDao.upsert(track.toFavoriteEntry(clock()))
    }

    suspend fun setFavorite(track: Track, saved: Boolean) {
        if (saved) addFavorite(track) else favoritesDao.deleteByUid(track.uid)
    }

    /** Adds the track to favorites, or removes it; returns the resulting state. */
    suspend fun toggleFavorite(track: Track): Boolean {
        if (favoritesDao.find(track.uid) != null) {
            favoritesDao.deleteByUid(track.uid)
            return false
        }
        favoritesDao.upsert(track.toFavoriteEntry(clock()))
        return true
    }

    suspend fun clearHistory() {
        historyDao.clear()
    }

    private suspend fun evictBeyondLimit() {
        val evict = HistoryPolicy.idsToEvict(historyDao.all().map { it.uid }, historyLimit)
        if (evict.isNotEmpty()) historyDao.deleteByUids(evict)
    }
}
