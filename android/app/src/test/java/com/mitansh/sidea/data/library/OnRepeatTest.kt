package com.mitansh.sidea.data.library

import com.grack.nanojson.JsonParser
import com.mitansh.sidea.core.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class OnRepeatTest {
    private val now = 100L * 86_400_000
    private val cutoff = now - OnRepeatPolicy.WINDOW_MS
    private fun stat(id: String, count: Int, last: Long = now - 1000, first: Long = now - 100_000) =
        ListeningStatEntry("youtube:$id", "artist", id, "Artist", 200_000, null, count, 0, 0, first, last)

    @Test fun ranksReplaysBeforeRecencyAndUsesStableTieBreaks() {
        val rows = listOf(stat("c", 3), stat("b", 5, now - 5000), stat("a", 3), stat("d", 3, now - 500))
        assertEquals(listOf("b", "d", "a", "c"), OnRepeatPolicy.tracks(rows, emptyList(), now).map { it.id })
    }

    @Test fun singleListensSkipsAndUnknownMetadataDoNotCreateAnEntry() {
        val rows = listOf(stat("once", 1), stat("skip", 0).copy(skipCount = 9), stat("blank", 8).copy(title = ""), stat("", 9))
        assertTrue(OnRepeatPolicy.tracks(rows, emptyList(), now).isEmpty())
    }

    @Test fun legacyLifetimeCountsAreNotAssignedToTheMostRecentDate() {
        val old = stat("old", 900, first = cutoff - 100_000)
        assertTrue(OnRepeatPolicy.tracks(listOf(old), emptyList(), now).isEmpty())
        val sessions = listOf(RecentPlayCount(old.uid, 2, now - 500))
        val recent = stat("recent", 3)
        assertEquals(listOf("recent", "old"), OnRepeatPolicy.tracks(listOf(old, recent), sessions, now).map { it.id })
    }

    @Test fun eligibleLegacyCountsArePreservedWithoutDoubleCountingNewEvents() {
        val row = stat("known", 5)
        val sessions = listOf(RecentPlayCount(row.uid, 2, now - 1000))
        assertEquals(5, OnRepeatPolicy.recentCount(row, 2, now))
        assertEquals(listOf("known", "other"), OnRepeatPolicy.tracks(listOf(row, stat("other", 4)), sessions, now).map { it.id })
    }

    @Test fun cutoffIsInclusiveAndOldAndFutureRecordsAreExcluded() {
        val edge = stat("edge", 2, last = now, first = cutoff)
        assertEquals(1, OnRepeatPolicy.tracks(listOf(edge), emptyList(), now).size)
        assertTrue(OnRepeatPolicy.tracks(listOf(edge), emptyList(), now + 1).isEmpty())
        assertTrue(OnRepeatPolicy.tracks(listOf(stat("future", 20, now + 1, now + 1)), emptyList(), now).isEmpty())
        assertTrue(OnRepeatPolicy.tracks(listOf(stat("old", 30, cutoff - 1, cutoff - 2)), emptyList(), now).isEmpty())
        assertTrue(OnRepeatPolicy.tracks(listOf(stat("old", 30, first = cutoff - 1)), listOf(RecentPlayCount("youtube:old", 30, now + 1)), now).isEmpty())
    }

    @Test fun limitsToThirtyAndPreservesSourceQualifiedIdentity() {
        val rows = (0..40).map { stat("song$it", 50 - it) } + stat("other", 100).copy(uid = "other:song0")
        val tracks = OnRepeatPolicy.tracks(rows, emptyList(), now)
        assertEquals(30, tracks.size)
        assertEquals("other:song0", tracks.first().uid)
        assertTrue(tracks.any { it.uid == "youtube:song0" })
    }

    @Test fun expiryRemovesLegacySongsWhenTheirDateRangeLeavesTheWindow() {
        val row = stat("expiring", 2, last = cutoff + 20, first = cutoff + 10)
        assertEquals(1, OnRepeatPolicy.tracks(listOf(row), emptyList(), now).size)
        assertTrue(OnRepeatPolicy.tracks(listOf(row), emptyList(), now + 11).isEmpty())
    }

    @Test fun qualifyingRecordCreatesExactlyOneEventAndOutcomeDoesNotAddAPlay() = runTest {
        val dao = MemoryStatsDao()
        val repo = ListeningStatsRepository(dao) { now }
        val track = Track("song", "Song", "Artist", 200_000)
        assertEquals(1, repo.recordPlay(track, completed = false))
        repo.recordCompletion(track)
        repo.recordSkip(track)
        assertEquals(1, dao.events.value.size)
        assertEquals(1, dao.find(track.uid)!!.playCount)
        assertEquals(1, dao.find(track.uid)!!.completedCount)
        assertEquals(1, dao.find(track.uid)!!.skipCount)
        assertEquals(2, repo.recordPlay(track, completed = true))
        assertEquals(2, dao.events.value.size)
        assertEquals(2, dao.find(track.uid)!!.completedCount)
        assertEquals(1, repo.observeOnRepeat().first().size)
    }

    @Test fun newlyRecordedSongAppearsWithoutWaitingForTheMinuteTick() = runTest {
        val dao = MemoryStatsDao()
        var time = now
        val repo = ListeningStatsRepository(dao) { time }
        val track = Track("fresh", "Fresh", "Artist")
        val waiting = async { repo.observeOnRepeat().first { it.isNotEmpty() } }
        testScheduler.runCurrent()
        time += 1000
        repo.recordPlay(track, false)
        time += 1000
        repo.recordPlay(track, false)
        assertEquals(listOf(track), waiting.await())
    }

    @Test fun clearingStatsAlsoRemovesTimestampedPlays() = runTest {
        val dao = MemoryStatsDao()
        val repo = ListeningStatsRepository(dao) { now }
        repo.recordPlay(Track("song", "Song", "Artist"), false)
        repo.clear()
        assertTrue(dao.all().isEmpty())
        assertTrue(dao.events.value.isEmpty())
    }

    @Test fun versionFiveMigrationMatchesRoomAndDoesNotRewriteLegacyData() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; else -> null }
        } as androidx.sqlite.db.SupportSQLiteDatabase
        SideADatabase.MIGRATION_4_5.migrate(database)
        val schema = File("schemas/com.mitansh.sidea.data.library.SideADatabase/5.json")
        assertTrue("Room v5 schema is exported", schema.isFile)
        val entities = JsonParser.`object`().from(schema.readText()).getObject("database").getArray("entities")
        val table = entities.filterIsInstance<com.grack.nanojson.JsonObject>().single { it.getString("tableName") == "listening_plays" }
        assertEquals(table.getString("createSql").replace("\${TABLE_NAME}", "listening_plays"), statements.first())
        for (index in table.getArray("indices").filterIsInstance<com.grack.nanojson.JsonObject>()) {
            assertTrue(statements.contains(index.getString("createSql").replace("\${TABLE_NAME}", "listening_plays")))
        }
        assertTrue(statements.all { it.startsWith("CREATE ") && it.contains("listening_plays") })
    }

    private class MemoryStatsDao : ListeningStatsDao() {
        private val rows = MutableStateFlow<List<ListeningStatEntry>>(emptyList())
        val events = MutableStateFlow<List<ListeningPlayEntry>>(emptyList())
        override suspend fun find(uid: String) = rows.value.firstOrNull { it.uid == uid }
        override suspend fun all() = rows.value
        override fun observeAll(): Flow<List<ListeningStatEntry>> = rows
        override suspend fun playedAtLeast(minPlays: Int) = rows.value.filter { it.playCount >= minPlays }
        override suspend fun count() = rows.value.size
        override suspend fun upsert(entry: ListeningStatEntry) { rows.value = rows.value.filterNot { it.uid == entry.uid } + entry }
        override suspend fun clearStats() { rows.value = emptyList() }
        override suspend fun clearPlays() { events.value = emptyList() }
        override suspend fun insertPlay(entry: ListeningPlayEntry) { events.value += entry }
        override suspend fun trimPlays(cutoff: Long) { events.value = events.value.filter { it.playedAtEpochMs >= cutoff } }
        override fun observeRecentPlays(cutoff: Long, now: Long): Flow<List<RecentPlayCount>> = combine(events, rows) { plays, _ ->
            plays.filter { it.playedAtEpochMs in cutoff..now }.groupBy { it.uid }
                .map { (uid, group) -> RecentPlayCount(uid, group.size, group.maxOf { it.playedAtEpochMs }) }
        }
    }
}
