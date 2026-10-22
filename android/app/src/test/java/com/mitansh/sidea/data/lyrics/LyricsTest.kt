package com.mitansh.sidea.data.lyrics

import com.grack.nanojson.JsonObject
import com.mitansh.sidea.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class LyricsTest {
    @get:Rule val folder = TemporaryFolder()
    private val track = Track("test", "Test Song", "Test Artist", 240_000)
    private val lyrics = Lyrics("First\nSecond", listOf(LyricLine(1200, "First"), LyricLine(2300, "Second")))

    @Test fun parsesFractionsRepeatedTimestampsAndSortsWithoutMetadata() {
        val lines = LrcParser.parse("[ar:Example]\n[00:02.3][00:04.030]Repeat\n[00:01]Start\n[00:03.12]\ninvalid")
        assertEquals(listOf(LyricLine(1000, "Start"), LyricLine(2300, "Repeat"), LyricLine(3120, ""), LyricLine(4030, "Repeat")), lines)
    }

    @Test fun appliesPositiveAndNegativeOffsets() {
        assertEquals(800L, LrcParser.parse("[offset:200]\n[00:01]Word").single().timeMs)
        assertEquals(1200L, LrcParser.parse("[offset:-200]\n[00:01]Word").single().timeMs)
        assertEquals(0L, LrcParser.parse("[offset:2000]\n[00:01]Word").single().timeMs)
    }

    @Test fun activeLineTracksForwardBackwardAndBeforeFirstTimestamp() {
        assertEquals(-1, lyrics.activeIndex(0))
        assertEquals(0, lyrics.activeIndex(1200))
        assertEquals(1, lyrics.activeIndex(10000))
        assertEquals(0, lyrics.activeIndex(1500))
        assertEquals(-1, Lyrics("plain", emptyList()).activeIndex(2000))
    }

    @Test fun cleansDecorativeYoutubeMetadataButPreservesRecordingVersion() {
        val query = LyricsQuery.from(track.copy(title = "Test Artist - Test Song (Official Music Video)", artist = "Test Artist - Topic"))
        assertEquals("Test Song", query.title)
        assertEquals("Test Artist", query.artist)
        assertEquals("Test Song (Live)", LyricsQuery.from(track.copy(title = "Test Song (Live)")).title)
        assertEquals("Test Song (Remix)", LyricsQuery.from(track.copy(title = "Test Song (Remix)")).title)
    }

    @Test fun unicodeNormalizationPreservesNonEnglishIdentity() {
        assertEquals(LyricsQuery.normalize("CAFÉ"), LyricsQuery.normalize("Cafe\u0301"))
        assertNotEquals(LyricsQuery.normalize("गीत"), LyricsQuery.normalize("Song"))
        assertNotEquals(LyricsQuery.normalize("गीत"), LyricsQuery.normalize("गत"))
    }

    private fun response(title: String = track.title, artist: String = track.artist, duration: Double = 240.0) = JsonObject().apply {
        put("trackName", title); put("artistName", artist); put("duration", duration)
        put("instrumental", false); put("plainLyrics", "First\nSecond"); put("syncedLyrics", "[00:01.200]First\n[00:02.300]Second")
    }

    @Test fun rejectsWrongArtistTitleAndRecordingDuration() {
        val source = LrclibSource()
        val query = LyricsQuery.from(track)
        assertNull(source.decode(response(artist = "Other Artist"), query))
        assertNull(source.decode(response(title = "Test Song (Live)"), query))
        assertNull(source.decode(response(duration = 250.0), query))
        assertNull(source.decode(response(duration = Double.NaN), query))
        assertEquals(lyrics, source.decode(response(), query))
    }

    @Test fun plainLyricsInstrumentalAndBlankPayloadHaveSeparateOutcomes() {
        val source = LrclibSource()
        val query = LyricsQuery.from(track)
        val json = response().apply { put("syncedLyrics", null) }
        assertFalse(source.decode(json, query)!!.isSynced)
        json["plainLyrics"] = " "
        assertNull(source.decode(json, query))
        json["instrumental"] = true
        assertTrue(source.decode(json, query)!!.instrumental)
    }

    @Test fun successfulLyricsPersistAcrossRepositoryInstancesAndWorkOffline() = runTest {
        val directory = folder.newFolder()
        val calls = AtomicInteger()
        val source = LyricsSource { calls.incrementAndGet(); lyrics }
        assertEquals(lyrics, LyricsRepository(directory, source).load(track, true))
        assertEquals(lyrics, LyricsRepository(directory, source).load(track, false))
        assertEquals(1, calls.get())
        assertTrue(directory.listFiles()!!.all { it.extension == "json" })
    }

    @Test fun offlineMissDoesNotCallNetworkOrCreateNegativeCache() = runTest {
        val directory = folder.newFolder()
        val calls = AtomicInteger()
        val repository = LyricsRepository(directory, LyricsSource { calls.incrementAndGet(); lyrics })
        try { repository.load(track, false); fail("Expected offline state") } catch (_: LyricsOfflineException) { }
        assertEquals(0, calls.get())
        assertEquals(lyrics, repository.load(track, true))
    }

    @Test fun missingAndFailedLookupsRemainRetryable() = runTest {
        val directory = folder.newFolder()
        var calls = 0
        val repository = LyricsRepository(directory, LyricsSource {
            when (++calls) { 1 -> null; 2 -> throw IOException("Unavailable"); else -> lyrics }
        })
        assertNull(repository.load(track, true))
        try { repository.load(track, true); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(lyrics, repository.load(track, true))
        assertEquals(3, calls)
    }

    @Test fun cancellationPropagatesWithoutSavingStaleLyrics() = runTest {
        val directory = folder.newFolder()
        val started = CompletableDeferred<Unit>()
        val repository = LyricsRepository(directory, LyricsSource { started.complete(Unit); awaitCancellation() })
        val request = async { repository.load(track, true) }
        started.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun corruptCacheIsRepairedAndCacheHasBoundedRecordCount() = runTest {
        val directory = folder.newFolder()
        val repository = LyricsRepository(directory, LyricsSource { lyrics })
        repository.load(track, true)
        directory.listFiles()!!.single().writeText("broken")
        assertEquals(lyrics, repository.load(track, true))
        repeat(104) { repository.load(track.copy(title = "Other Song $it"), true) }
        assertEquals(100, directory.listFiles()!!.size)
    }

    @Test fun transportUsesSearchAfter404AndSelectsMatchingSyncedRecording() = runTest {
        val requests = mutableListOf<String>()
        val json = com.grack.nanojson.JsonWriter.string(response())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().url.toString())
            val direct = chain.request().url.encodedPath.endsWith("/get")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (direct) 404 else 200)
                .message("fixture").body((if (direct) "{}" else "[$json]").toResponseBody()).build()
        }.build()
        assertEquals(lyrics, LrclibSource(client).find(LyricsQuery.from(track)))
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("duration=240.0"))
        assertTrue(requests[1].contains("/api/search"))
    }

    @Test fun transportRejectsOversizedResponsesAndProviderErrors() = runTest {
        for ((code, body) in listOf(200 to "x".repeat(512 * 1024 + 1), 503 to "busy")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
            }.build()
            try { LrclibSource(client).find(LyricsQuery.from(track)); fail("Expected bounded provider error") } catch (_: IOException) { }
        }
    }
}
