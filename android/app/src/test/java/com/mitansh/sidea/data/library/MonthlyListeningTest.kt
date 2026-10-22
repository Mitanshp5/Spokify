package com.mitansh.sidea.data.library

import org.junit.Assert.*
import org.junit.Test

class MonthlyListeningTest {
    private fun stat(uid: String, artist: String = "Artist", lifetime: Int = 100) = ListeningStatEntry(
        uid, artist.lowercase(), "Song $uid", artist, 180_000, null, lifetime, 0, 0, 1, 100)

    @Test fun usesOnlyQualifiedSessionsInTheRequestedMonthNeverLifetimeCounts() {
        val result = MonthlyListeningPolicy.summarize(listOf(stat("youtube:old"), stat("youtube:new", lifetime = 2)),
            listOf(RecentPlayCount("youtube:new", 2, 100)))
        assertEquals(2, result.plays)
        assertEquals(listOf("youtube:new"), result.songs.map { it.track.uid })
    }

    @Test fun keepsSourceIdentityAndRanksArtistByMonthlyCounts() {
        val stats = listOf(stat("youtube:same", "A"), stat("local:same", "B"), stat("youtube:other", "A"))
        val result = MonthlyListeningPolicy.summarize(stats,
            listOf(RecentPlayCount("youtube:same", 2, 100), RecentPlayCount("local:same", 3, 100), RecentPlayCount("youtube:other", 2, 100)))
        assertEquals(3, result.songs.size)
        assertEquals("local:same", result.songs.first().track.uid)
        assertEquals(listOf("A" to 4, "B" to 3), result.artists)
    }

    @Test fun ignoresMissingMetadataAndNonPositiveCountsWithStableTies() {
        val result = MonthlyListeningPolicy.summarize(listOf(stat("youtube:b"), stat("youtube:a"), stat("youtube:zero")),
            listOf(RecentPlayCount("youtube:missing", 30, 100), RecentPlayCount("youtube:b", 1, 100),
                RecentPlayCount("youtube:zero", 0, 100), RecentPlayCount("youtube:a", 1, 100)))
        assertEquals(listOf("youtube:a", "youtube:b"), result.songs.map { it.track.uid })
        assertEquals(2, result.plays)
    }
}
