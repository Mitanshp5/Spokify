package com.mitansh.sidea.data.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class SpotifyPaginationTest {
    private val id = "6qFMjTCsg6JuL39PvsSOSY"
    private fun embed() = """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"settings":{"session":{"accessToken":"anonymous-fixture"}},"data":{"entity":{"name":"Bhavya's","trackList":[{"title":"Preview only","subtitle":"Artist","duration":180000}]}}}}}}</script>"""
    private fun row(index: Int) = """{"itemV2":{"data":{"__typename":"Track","name":"Song $index","uri":"spotify:track:0123456789ABCDEFGHIJKL","artists":{"items":[{"profile":{"name":"First Artist"}},{"profile":{"name":"Second Artist"}}]},"duration":{"totalMilliseconds":180123}}}}"""
    private fun page(offset: Int, size: Int, total: Int, next: Int?) = """{"data":{"playlistV2":{"uri":"spotify:playlist:$id","name":"Bhavya's","content":{"totalCount":$total,"pagingInfo":{"nextOffset":$next},"items":[${(offset until offset + size).joinToString(",") { row(it) }}]}}}}"""
    private fun offset(url: String): Int {
        val query = URLDecoder.decode(url.substringAfter('?'), "UTF-8")
        return Regex("\"offset\":(\\d+)").find(query)!!.groupValues[1].toInt()
    }

    @Test fun readsAll133RowsThroughThePublicReaderInSourceOrder() = runBlocking {
        val offsets = mutableListOf<Int>()
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            if (url.contains("/embed/")) SpotifyApiPage(embed()) else {
                val offset = offset(url); offsets += offset
                SpotifyApiPage(if (offset == 0) page(0, 100, 133, 100) else page(100, 33, 133, null))
            }
        })
        val result = repository.publicPlaylist("https://open.spotify.com/playlist/$id?si=sample")
        assertEquals(listOf(0, 100), offsets)
        assertEquals(133, result.tracks.size)
        assertEquals(133, result.declaredCount)
        assertEquals(SpotifyCompleteness.COMPLETE, result.completeness)
        assertEquals((0 until 133).toList(), result.tracks.map { it.position })
        assertEquals("Song 132", result.tracks.last().title)
        assertEquals("First Artist, Second Artist", result.tracks.first().artist)
        assertEquals(180123L, result.tracks.first().durationMs)
        assertEquals(1, result.tracks.map { it.sourceId }.distinct().size)
    }

    @Test fun unavailableRowsKeepTheirPlaceAcrossPages() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            SpotifyApiPage(if (url.contains("/embed/")) embed() else
                page(0, 2, 2, null).replace(row(0), """{"itemV2":{"data":null}}"""))
        })
        val result = repository.publicPlaylist(id)
        assertEquals(2, result.tracks.size)
        assertEquals("", result.tracks[0].title)
        assertNull(result.tracks[0].sourceId)
        assertEquals("Song 1", result.tracks[1].title)
        assertEquals(SpotifyCompleteness.COMPLETE, result.completeness)
    }

    @Test fun failedLaterPageKeepsFetchedRowsButReportsPartial() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            if (url.contains("/embed/")) SpotifyApiPage(embed())
            else if (offset(url) == 0) SpotifyApiPage(page(0, 100, 133, 100))
            else throw SpotifyImportException(SpotifyFailure.RATE_LIMITED, "Rate limited")
        })
        val result = repository.publicPlaylist(id)
        assertEquals(100, result.tracks.size)
        assertEquals(133, result.declaredCount)
        assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
        assertTrue(result.warning.orEmpty().contains("100"))
        assertNotNull(result.warning)
    }

    @Test fun repeatedCursorCannotLoopOrClaimCompleteness() = runBlocking {
        var calls = 0
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            calls++
            SpotifyApiPage(if (url.contains("/embed/")) embed() else page(0, 100, 133, 0))
        })
        val result = repository.publicPlaylist(id)
        assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
        assertTrue(calls <= 3)
        assertNotNull(result.warning)
    }

    @Test fun missingTerminalRowsAreNotCalledComplete() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            SpotifyApiPage(if (url.contains("/embed/")) embed() else page(0, 100, 133, null))
        })
        val result = repository.publicPlaylist(id)
        assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
        assertEquals(133, result.declaredCount)
        assertNotNull(result.warning)
    }

    @Test fun unavailableFullReaderFallsBackWithAnExplicitPreviewWarning() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            if (url.contains("/embed/")) SpotifyApiPage(embed()) else throw SpotifyImportException(SpotifyFailure.NETWORK, "Offline")
        })
        val result = repository.publicPlaylist(id)
        assertEquals("Preview only", result.tracks.single().title)
        assertNotEquals(SpotifyCompleteness.COMPLETE, result.completeness)
        assertNotNull(result.warning)
    }

    @Test fun cancellationDuringPaginationIsNotSwallowed() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            if (url.contains("/embed/")) SpotifyApiPage(embed()) else throw CancellationException("cancel")
        })
        try { repository.publicPlaylist(id); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun safetyLimitIsExplicitAndDoesNotRequestAnUnboundedNumberOfPages() = runBlocking {
        val offsets = mutableListOf<Int>()
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            if (url.contains("/embed/")) SpotifyApiPage(embed()) else {
                val offset = offset(url); offsets += offset
                SpotifyApiPage(page(offset, 100, 2001, offset + 100))
            }
        })
        val result = repository.publicPlaylist(id)
        assertEquals((0 until 2000 step 100).toList(), offsets)
        assertEquals(2000, result.tracks.size)
        assertEquals(2001, result.declaredCount)
        assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
        assertTrue(result.warning.orEmpty().contains("safety limit"))
    }

    @Test fun changedPlaylistCountCannotProduceAFalseCompleteResult() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            SpotifyApiPage(when {
                url.contains("/embed/") -> embed()
                offset(url) == 0 -> page(0, 100, 133, 100)
                else -> page(100, 34, 134, null)
            })
        })
        val result = repository.publicPlaylist(id)
        assertEquals(100, result.tracks.size)
        assertEquals(133, result.declaredCount)
        assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
        assertNotNull(result.warning)
    }

    @Test fun anUnrelatedPlaylistResponseIsNotImported() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            SpotifyApiPage(if (url.contains("/embed/")) embed() else
                page(0, 1, 1, null).replace("spotify:playlist:$id", "spotify:playlist:0123456789ABCDEFGHIJKL"))
        })
        val result = repository.publicPlaylist(id)
        assertEquals("Preview only", result.tracks.single().title)
        assertEquals(SpotifyCompleteness.UNKNOWN, result.completeness)
        assertNotNull(result.warning)
    }

    @Test fun emptyFirstApiPagesRetainTheUsablePreviewAndKnownTotal() = runBlocking {
        for (next in listOf(null, 0)) {
            var requests = 0
            val repository = SpotifyRepository(SpotifyApiTransport { url ->
                requests++
                SpotifyApiPage(if (url.contains("/embed/")) embed() else page(0, 0, 133, next))
            })
            val result = repository.publicPlaylist(id)
            assertEquals("Preview only", result.tracks.singleOrNull()?.title)
            assertEquals(id, result.id)
            assertEquals(133, result.declaredCount)
            assertEquals(SpotifyCompleteness.PARTIAL, result.completeness)
            assertNotNull(result.warning)
            assertEquals(2, requests)
        }
    }

    @Test fun fractionalAndOverflowCountsCannotBecomeCompletePlaylists() = runBlocking {
        for (invalid in listOf("1.5", "4294967297", "-1", "1e100")) {
            val repository = SpotifyRepository(SpotifyApiTransport { url ->
                SpotifyApiPage(if (url.contains("/embed/")) embed() else
                    page(0, 1, 1, null).replace("\"totalCount\":1", "\"totalCount\":$invalid"))
            })
            val result = repository.publicPlaylist(id)
            assertEquals(invalid, "Preview only", result.tracks.singleOrNull()?.title)
            assertEquals(invalid, SpotifyCompleteness.UNKNOWN, result.completeness)
            assertNotNull(result.warning)
        }
    }

    @Test fun fractionalAndOverflowCursorsAreRejectedInsteadOfTruncated() = runBlocking {
        for (invalid in listOf("100.5", "4294967396", "-1", "1e100")) {
            var requests = 0
            val repository = SpotifyRepository(SpotifyApiTransport { url ->
                requests++
                SpotifyApiPage(when {
                    url.contains("/embed/") -> embed()
                    offset(url) == 0 -> page(0, 100, 133, 100).replace("\"nextOffset\":100", "\"nextOffset\":$invalid")
                    else -> page(100, 33, 133, null)
                })
            })
            val result = repository.publicPlaylist(id)
            assertEquals(invalid, "Preview only", result.tracks.singleOrNull()?.title)
            assertEquals(invalid, SpotifyCompleteness.UNKNOWN, result.completeness)
            assertNotNull(result.warning)
            assertEquals(2, requests)
        }
    }

    @Test fun legitimatelyEmptyApiPlaylistIsStillComplete() = runBlocking {
        val repository = SpotifyRepository(SpotifyApiTransport { url ->
            SpotifyApiPage(if (url.contains("/embed/")) embed() else page(0, 0, 0, null))
        })
        val result = repository.publicPlaylist(id)
        assertTrue(result.tracks.isEmpty())
        assertEquals(0, result.declaredCount)
        assertEquals(SpotifyCompleteness.COMPLETE, result.completeness)
        assertNull(result.warning)
    }

    @Test fun malformedOrContradictoryEmbedCountsAreNotReportedComplete() = runBlocking {
        for (invalid in listOf("-1", "0", "1.5", "4294967297")) {
            val html = embed().replace("\"accessToken\":\"anonymous-fixture\"", "\"noToken\":true")
                .replace("\"name\":\"Bhavya's\"", "\"trackCount\":$invalid,\"name\":\"Bhavya's\"")
            val repository = SpotifyRepository(SpotifyApiTransport { SpotifyApiPage(html) })
            try { repository.publicPlaylist(id); fail("Malformed embed count was accepted: $invalid") }
            catch (error: SpotifyImportException) { assertEquals(SpotifyFailure.PROVIDER_CHANGED, error.kind) }
        }
    }
}
