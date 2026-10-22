package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track

object OnRepeatPolicy {
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_TRACKS = 30

    /**
     * Legacy totals are usable only when their entire known date range fits the window.
     * Otherwise use timestamped sessions; never move lifetime plays to the last-play date.
     */
    fun recentCount(stat: ListeningStatEntry, sessions: Int, nowMs: Long, windowMs: Long = WINDOW_MS): Int =
        if (stat.firstPlayedAtEpochMs >= nowMs - windowMs && stat.firstPlayedAtEpochMs <= stat.lastPlayedAtEpochMs && stat.lastPlayedAtEpochMs <= nowMs) {
            stat.playCount.coerceAtLeast(0)
        } else sessions.coerceAtLeast(0)

    fun tracks(stats: List<ListeningStatEntry>, sessions: List<RecentPlayCount>, nowMs: Long): List<Track> {
        val counts = sessions.associateBy { it.uid }
        return stats.mapNotNull { stat ->
            val recent = counts[stat.uid]?.takeIf { it.lastPlayedAtEpochMs in (nowMs - WINDOW_MS)..nowMs }
            val count = recentCount(stat, recent?.playCount ?: 0, nowMs)
            if (count < 2 || stat.title.isBlank() || stat.artist.isBlank() || ':' !in stat.uid || stat.uid.substringAfter(':').isBlank() || stat.uid.substringBefore(':').isBlank()) null
            else Triple(stat, count, recent?.lastPlayedAtEpochMs ?: stat.lastPlayedAtEpochMs)
        }.sortedWith(compareByDescending<Triple<ListeningStatEntry, Int, Long>> { it.second }
            .thenByDescending { it.third }.thenBy { it.first.uid })
            .distinctBy { it.first.uid }.take(MAX_TRACKS).map { (stat, _, _) ->
                Track(stat.uid.substringAfter(':'), stat.title, stat.artist, stat.durationMs, stat.artworkUrl, stat.uid.substringBefore(':'))
            }
    }
}
