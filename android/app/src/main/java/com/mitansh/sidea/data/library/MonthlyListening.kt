package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track

data class CapsuleSong(val track: Track, val plays: Int)
data class MonthlyListening(val songs: List<CapsuleSong> = emptyList()) {
    val plays: Int get() = songs.sumOf { it.plays }
    val artists: List<Pair<String, Int>> get() = songs.groupBy { it.track.artist }
        .map { (artist, songs) -> artist to songs.sumOf { it.plays } }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}

object MonthlyListeningPolicy {
    fun summarize(stats: List<ListeningStatEntry>, sessions: List<RecentPlayCount>): MonthlyListening {
        val metadata = stats.associateBy { it.uid }
        return MonthlyListening(sessions.filter { it.playCount > 0 }.mapNotNull { session ->
            val stat = metadata[session.uid] ?: return@mapNotNull null
            if (':' !in stat.uid || stat.title.isBlank()) return@mapNotNull null
            CapsuleSong(Track(stat.uid.substringAfter(':'), stat.title, stat.artist, stat.durationMs, stat.artworkUrl, stat.uid.substringBefore(':')), session.playCount)
        }.sortedWith(compareByDescending<CapsuleSong> { it.plays }.thenBy { it.track.uid }))
    }
}
