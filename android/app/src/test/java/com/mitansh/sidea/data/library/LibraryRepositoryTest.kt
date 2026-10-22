package com.mitansh.sidea.data.library

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {

    private val now = 1_700_000_000_000L

    private fun track(id: String, title: String = "Song $id") = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        durationMs = 200_000,
        artworkUrl = "https://example.invalid/$id.jpg",
    )

    @Test
    fun `records the first play with full metadata and a play count of one`() = runTest {
        val repository = LibraryRepository(FakeHistoryDao(), FakeFavoritesDao(), clock = { now })

        repository.recordPlayed(track("aaaaaaaaaaa", title = "First"))

        val stored = repository.observeHistory().first().single()
        assertEquals("youtube:aaaaaaaaaaa", stored.uid)
        assertEquals("First", stored.title)
        assertEquals("Artist aaaaaaaaaaa", stored.artist)
        assertEquals(1, repository.playCount("youtube:aaaaaaaaaaa"))
    }

    @Test
    fun `replaying a track updates its timestamp instead of duplicating it`() = runTest {
        var clock = now
        val dao = FakeHistoryDao()
        val repository = LibraryRepository(dao, FakeFavoritesDao(), clock = { clock })

        repository.recordPlayed(track("aaaaaaaaaaa"))
        clock = now + 60_000
        repository.recordPlayed(track("bbbbbbbbbbb"))
        clock = now + 120_000
        repository.recordPlayed(track("aaaaaaaaaaa"))

        assertEquals(2, dao.rows.size)
        assertEquals(listOf("youtube:aaaaaaaaaaa", "youtube:bbbbbbbbbbb"), dao.all().map { it.uid })
        assertEquals(2, repository.playCount("youtube:aaaaaaaaaaa"))
        assertEquals(now + 120_000, dao.find("youtube:aaaaaaaaaaa")!!.lastPlayedAtEpochMs)
    }

    @Test
    fun `evicts the oldest entries once history exceeds its bound`() = runTest {
        val dao = FakeHistoryDao()
        var clock = now
        val repository = LibraryRepository(dao, FakeFavoritesDao(), clock = { clock }, historyLimit = 3)

        for (index in 1..5) {
            repository.recordPlayed(track("id%09d".format(index)))
            clock += 1_000
        }

        assertEquals(3, dao.rows.size)
        assertEquals(
            listOf("youtube:id000000005", "youtube:id000000004", "youtube:id000000003"),
            dao.all().map { it.uid },
        )
    }

    @Test
    fun `toggling a favorite adds then removes it`() = runTest {
        val repository = LibraryRepository(FakeHistoryDao(), FakeFavoritesDao(), clock = { now })

        assertTrue(repository.toggleFavorite(track("aaaaaaaaaaa")))
        assertTrue(repository.isFavorite("youtube:aaaaaaaaaaa"))
        assertEquals(1, repository.observeFavorites().first().size)

        assertFalse(repository.toggleFavorite(track("aaaaaaaaaaa")))
        assertFalse(repository.isFavorite("youtube:aaaaaaaaaaa"))
        assertTrue(repository.observeFavorites().first().isEmpty())
    }

    @Test
    fun `exposes history ordered most recently played first`() = runTest {
        var clock = now
        val repository = LibraryRepository(FakeHistoryDao(), FakeFavoritesDao(), clock = { clock })

        repository.recordPlayed(track("aaaaaaaaaaa"))
        clock = now + 1_000
        repository.recordPlayed(track("bbbbbbbbbbb"))

        assertEquals(
            listOf("youtube:bbbbbbbbbbb", "youtube:aaaaaaaaaaa"),
            repository.observeHistory().first().map { it.uid },
        )
    }

    @Test
    fun `importing an existing liked song twice preserves the favorite and its saved date`() = runTest {
        val dao = FakeFavoritesDao()
        var clock = now
        val repository = LibraryRepository(FakeHistoryDao(), dao, clock = { clock })
        repository.addFavorite(track("aaaaaaaaaaa"))
        clock += 10_000
        repository.addFavorite(track("aaaaaaaaaaa"))
        assertEquals(1, dao.rows.size)
        assertEquals(now, dao.rows.single().savedAtEpochMs)
        assertTrue(repository.isFavorite("youtube:aaaaaaaaaaa"))
        assertTrue(repository.observeHistory().first().isEmpty())
    }

    @Test
    fun `clearing history leaves favorites untouched`() = runTest {
        val repository = LibraryRepository(FakeHistoryDao(), FakeFavoritesDao(), clock = { now })

        repository.recordPlayed(track("aaaaaaaaaaa"))
        repository.toggleFavorite(track("aaaaaaaaaaa"))
        repository.clearHistory()

        assertTrue(repository.observeHistory().first().isEmpty())
        assertEquals(1, repository.observeFavorites().first().size)
    }

    @Test fun `explicit picker save is idempotent and removal leaves other songs intact`() = runTest {
        val repository = LibraryRepository(FakeHistoryDao(), FakeFavoritesDao(), clock = { now })
        val one = track("one")
        val two = track("two")
        repeat(2) { repository.setFavorite(one, true) }
        repository.setFavorite(two, true)
        assertEquals(2, repository.observeFavorites().first().size)
        repeat(2) { repository.setFavorite(one, false) }
        assertEquals(listOf(two), repository.observeFavorites().first())
    }
}

class FakeHistoryDao : HistoryDao {
    val rows = mutableListOf<HistoryEntry>()
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())

    private fun publish() {
        state.value = rows.sortedByDescending { it.lastPlayedAtEpochMs }
    }

    override suspend fun all(): List<HistoryEntry> =
        rows.sortedByDescending { it.lastPlayedAtEpochMs }

    override fun observe(limit: Int) = state

    override suspend fun find(uid: String): HistoryEntry? = rows.firstOrNull { it.uid == uid }

    override suspend fun upsert(entry: HistoryEntry) {
        rows.removeAll { it.uid == entry.uid }
        rows.add(entry)
        publish()
    }

    override suspend fun deleteByUids(uids: List<String>) {
        rows.removeAll { it.uid in uids }
        publish()
    }

    override suspend fun clear() {
        rows.clear()
        publish()
    }
}

class FakeFavoritesDao : FavoritesDao {
    val rows = mutableListOf<FavoriteEntry>()
    private val state = MutableStateFlow<List<FavoriteEntry>>(emptyList())

    private fun publish() {
        state.value = rows.sortedByDescending { it.savedAtEpochMs }
    }

    override fun observe() = state

    override suspend fun find(uid: String): FavoriteEntry? = rows.firstOrNull { it.uid == uid }

    override suspend fun upsert(entry: FavoriteEntry) {
        rows.removeAll { it.uid == entry.uid }
        rows.add(entry)
        publish()
    }

    override suspend fun deleteByUid(uid: String) {
        rows.removeAll { it.uid == uid }
        publish()
    }
}
