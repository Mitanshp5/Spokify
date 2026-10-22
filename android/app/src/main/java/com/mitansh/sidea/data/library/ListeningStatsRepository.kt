package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.youtube.SongRadioMixer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Durable listening statistics, kept for radio ranking and frequent-play saving.
 *
 * The recent-history list is bounded and evicts old rows, so a track's play count would be lost
 * once it falls out of that window. These counts are not bounded by the history limit; they are
 * trimmed only by an explicit reset, and they accumulate from real playback rather than from
 * taps.
 */
class ListeningStatsRepository(
    private val dao: ListeningStatsDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeAll(): Flow<List<ListeningStatEntry>> = dao.observeAll()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonthly(): Flow<MonthlyListening> = flow {
        while (true) { emit(clock()); delay(60_000) }
    }.flatMapLatest { now ->
        val month = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().withDayOfMonth(1)
        val start = month.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        combine(dao.observeAll(), dao.observeRecentPlays(start, end)) { stats, sessions ->
            MonthlyListeningPolicy.summarize(stats, sessions)
        }
    }.distinctUntilChanged()

    /** Re-query as time passes too, so old listens expire even while the screen stays open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOnRepeat(): Flow<List<Track>> = flow {
        while (true) { emit(clock()); delay(60_000) }
    }.flatMapLatest { now ->
        combine(dao.observeAll(), dao.observeRecentPlays(now - OnRepeatPolicy.WINDOW_MS, Long.MAX_VALUE)) { stats, sessions ->
            OnRepeatPolicy.tracks(stats, sessions, clock())
        }
    }.distinctUntilChanged()

    suspend fun all(): List<ListeningStatEntry> = dao.all()

    suspend fun find(uid: String): ListeningStatEntry? = dao.find(uid)

    /**
     * The songs played often enough to be saved automatically.
     *
     * Only listens inside the recent window count, so a song that was played a lot months ago is
     * not treated as frequently played now. The window is the same one the plan uses for the
     * recommendation weights.
     */
    suspend fun frequentlyPlayed(
        minPlays: Int,
        windowMs: Long,
        nowMs: Long = clock(),
    ): List<ListeningStatEntry> = dao.all()
        .filter { it.playCount >= minPlays && it.lastPlayedAtEpochMs >= nowMs - windowMs }
        .sortedByDescending { it.playCount }

    /** The number of listens of [track] inside the recent window. */
    suspend fun recentPlayCount(track: Track, windowMs: Long, nowMs: Long = clock()): Int {
        val entry = dao.find(track.uid) ?: return 0
        return if (entry.lastPlayedAtEpochMs >= nowMs - windowMs) entry.playCount else 0
    }

    /** Records a qualified listen and returns the track's new play count. */
    suspend fun recordPlay(track: Track, completed: Boolean): Int {
        val now = clock()
        val entry = ListeningStatEntry(
            uid = track.uid,
            artistKey = SongRadioMixer.normalizeArtist(track.artist),
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl,
            playCount = 0,
            completedCount = 0,
            skipCount = 0,
            firstPlayedAtEpochMs = now,
            lastPlayedAtEpochMs = now,
        )
        return dao.recordQualified(entry, completed)
    }

    /**
     * Records that a track was listened to from start to finish.
     *
     * The play itself is counted when it qualifies, which can happen before the song ends, so the
     * completion is a separate count. The ranking uses it to tell a song that was finished from one
     * that was merely left running.
     */
    suspend fun recordCompletion(track: Track) = dao.recordOutcome(metadata(track), completed = true)

    /** Records an abandoned recording, so it is not mistaken for a track that was enjoyed. */
    suspend fun recordSkip(track: Track) = dao.recordOutcome(metadata(track), completed = false)

    private fun metadata(track: Track): ListeningStatEntry {
        val now = clock()
        return ListeningStatEntry(track.uid, SongRadioMixer.normalizeArtist(track.artist), track.title, track.artist,
            track.durationMs, track.artworkUrl, 0, 0, 0, now, now)
    }

    suspend fun clear() = dao.clear()
}
