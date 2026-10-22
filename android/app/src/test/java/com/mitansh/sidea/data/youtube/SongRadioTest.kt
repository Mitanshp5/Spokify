package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SongRadioTest {
    @Test fun parsesOrderedPanelRowsAndContinuationExcludingSeed() {
        val continuationRow = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}"""
        val json = panel(listOf(row("seed0000001", "Seed", "A", "1:00"), row("video000001", "First", "B", "2:03"), row("video000002", "Second", "C", "3:04"), continuationRow).joinToString(","))
        val page = SongRadioParser.parse(json, Track("seed0000001", "Seed", "A"))
        assertEquals(listOf("video000001", "video000002"), page.tracks.map { it.id })
        assertEquals("First", page.tracks.first().title)
        assertEquals(123_000L, page.tracks.first().durationMs)
        assertEquals("https://img/video000001", page.tracks.first().artworkUrl)
        assertEquals("next-page", page.continuation)
    }

    @Test fun mixerExcludesSeedAndRepeatsAndFavorsLikedTracksWithoutLeavingUpstreamOrder() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "Same"), Track("b", "Song B", "Other"), Track("c", "Song C", "Same"))
        val liked = SongRadioMixer.mix(upstream, seed, setOf("b"), emptyMap(), limit = 2)
        assertEquals(listOf("b", "a"), liked.map { it.id })
        assertTrue(SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), excludedIds = setOf("a"), limit = 3).none { it.id == "a" })
    }

    @Test fun lateFavoriteDoesNotLeapfrogMuchMoreRelevantUpstreamResult() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf("a", "b", "c", "d", "e", "f").map { Track(it, "Song $it", "Artist $it") }
        val output = SongRadioMixer.mix(upstream, seed, setOf("f"), emptyMap(), limit = 6)
        assertEquals("a", output.first().id)
        assertTrue(output.indexOfFirst { it.id == "a" } < output.indexOfFirst { it.id == "f" })
    }

    @Test fun explicitSkipDemotionOutranksFavoriteBoost() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "Artist A"), Track("b", "Song B", "Artist B"))
        val output = SongRadioMixer.mix(upstream, seed, setOf("a"), mapOf("a" to 1), limit = 2)
        assertEquals(listOf("b", "a"), output.map { it.id })
    }

    @Test fun normalizedArtistSpacingAcrossLastTwoPicksWhenAlternativesExist() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(
            Track("a", "Song A", "Band"),
            Track("b", "Song B", "Other"),
            Track("c", "Song C", "b.a.n.d!"),
            Track("d", "Song D", "Third"),
        )
        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 4)
        assertEquals(listOf("a", "b", "d", "c"), output.map { it.id })
    }

    @Test fun soleArtistListStillFillsLimit() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf("a", "b", "c").map { Track(it, "Song $it", "Same Artist") }
        assertEquals(3, SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), limit = 3).size)
    }

    @Test fun intMaxSkipCountCannotOverflowAndFavoriteSkipStaysDemoted() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(Track("a", "Song A", "Artist A"), Track("b", "Song B", "Artist B"))
        val output = SongRadioMixer.mix(upstream, seed, setOf("a"), mapOf("a" to Int.MAX_VALUE), limit = 2)
        assertEquals(listOf("b", "a"), output.map { it.id })
    }

    @Test fun seedQueuedAndRecordingExclusionsStayIntact() {
        val seed = Track("seed0000001", "Song", "Artist")
        val queued = listOf(Track("queued00001", "Queued Song", "Queue Artist"))
        val upstream = listOf(
            Track("seed0000001", "Song", "Artist"),
            Track("queued00001", "Queued Song", "Queue Artist"),
            Track("dup00000001", "QUEUED-SONG", "queue artist"),
            Track("explicit001", "Ignored", "Someone"),
            Track("ok00000001", "Other", "Other"),
            Track("ok00000001", "Other", "Other"),
        )
        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(), excludedIds = setOf("explicit001"), excludedTracks = queued, limit = 10)
        assertEquals(listOf("ok00000001"), output.map { it.id })
    }

    @Test fun repeatedRecordingsAndNoContinuationAreHandled() {
        val page = SongRadioParser.parse(panel("${row("video000003", "Same song", "Artist", "1:00")},${row("video000003", "Same song", "Artist", "1:00")}"), Track("seed0000001", "Seed", "Seed artist"))
        assertEquals(listOf("video000003"), page.tracks.map { it.id })
        assertEquals(null, page.continuation)
    }

    @Test fun continuationCursorIsPreservedAndExhaustedResultsStayEmpty() {
        val page = SongRadioParser.parse("""{"continuationContents":{"playlistPanelContinuation":{"contents":[],"continuations":[{"nextRadioContinuationData":{"continuation":"page-2"}}]}}}""", Track("seed0000001", "Seed", "Artist"))
        assertEquals("page-2", page.continuation)
        assertTrue(SongRadioMixer.mix(emptyList(), Track("seed", "Seed", "Artist"), emptySet(), emptyMap(), limit = 5).isEmpty())
    }

    @Test fun ignoresUnrelatedRendererAndTrackingTokensAndUsesOnlyPerformerCredits() {
        val json = """{"trackingParams":{"token":"not-a-page","continuation":"tracking"},"unrelated":{"playlistPanelVideoRenderer":{}},"contents":${panelContents(row("correct0001", "Correct", "Artist Name • Album Name • 2020", "1:00"))}}"""
        val page = SongRadioParser.parse(json, Track("seed0000001", "Seed", "Seed artist"))
        assertEquals(listOf("correct0001"), page.tracks.map { it.id })
        assertEquals("Artist Name", page.tracks.single().artist)
        assertEquals(null, page.continuation)
    }

    @Test fun dropsSeedRecordingAcrossAlternateIdsAndMixerExcludesQueuedRecordingsWithBoundedLimit() {
        val json = panel("${row("seed0000001", "Song", "Artist", "1:00")},${row("other000001", "S.O.N.G", "ARTIST", "1:00")}")
        assertTrue(SongRadioParser.parse(json, Track("seed0000001", "Song", "Artist")).tracks.isEmpty())
        val seed = Track("seed0000001", "Song", "Artist")
        val queued = listOf(Track("queued00001", "Queued Song", "Queue Artist"))
        val output = SongRadioMixer.mix(listOf(Track("different0001", "Queued-Song", "queue artist"), Track("ok00000001", "Other", "Other")), seed, emptySet(), emptyMap(), excludedTracks = queued, limit = Int.MAX_VALUE)
        assertEquals(listOf("ok00000001"), output.map { it.id })
    }

    @Test fun invalidJsonFailsRatherThanLookingLikeExhaustion() {
        assertThrows(SongRadioParseException::class.java) { SongRadioParser.parse("{bad", Track("seed0000001", "Seed", "Artist")) }
        val deeplyNested = "{" + "\"x\":".repeat(2_000) + "null" + "}".repeat(2_000)
        assertThrows(SongRadioParseException::class.java) { SongRadioParser.parse(deeplyNested, Track("seed0000001", "Seed", "Artist")) }
    }

    @Test fun liveSongRadioReturnsNonSeedCandidatesWhenOptedIn() = runBlocking {
        if (System.getenv("SONG_RADIO_LIVE") != "1") return@runBlocking
        val page = SongRadioRepository().fetch(Track("4D7u5KF7SP8", "Get Lucky", "Daft Punk"))
        assertTrue(page.tracks.isNotEmpty())
        assertTrue(page.tracks.none { it.id == "4D7u5KF7SP8" })
    }

    @Test fun repeatedlySkippedArtistDemotesUnseenSongsWithoutBanningThem() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(
            Track("x1", "Song X1", "Skipped Artist"),
            Track("x2", "Song X2", "SKIPPED-ARTIST"),
            Track("y1", "Song Y1", "Fine Artist"),
            Track("y2", "Song Y2", "Fine Artist"),
        )
        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(),
            artistWeights = mapOf("skippedartist" to 3), limit = 4)
        assertEquals(4, output.size)
        assertEquals("y1", output.first().id)
        assertTrue(output.indexOfFirst { it.id == "y1" } < output.indexOfFirst { it.id == "x1" })
        assertTrue(output.indexOfFirst { it.id == "y2" } < output.indexOfFirst { it.id == "x2" })
    }

    @Test fun artistDemotionStillAppliesWhenTheSkippedRecordingIdIsExcluded() {
        val seed = Track("seed", "Start", "Seed Artist")
        val upstream = listOf(
            Track("seen0000001", "Old Song", "Skipped Artist"),
            Track("x2", "New Song", "Skipped Artist"),
            Track("y1", "Fine Song", "Fine Artist"),
        )
        val output = SongRadioMixer.mix(upstream, seed, emptySet(), emptyMap(),
            excludedIds = setOf("seen0000001"),
            artistWeights = mapOf("skippedartist" to 3), limit = 3)
        assertFalse(output.any { it.id == "seen0000001" })
        assertEquals(listOf("y1", "x2"), output.map { it.id })
    }

    private fun row(id: String, title: String, artist: String, duration: String) =
        """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},"longBylineText":{"runs":[${artist.split(" • ").flatMapIndexed { index, part -> (if (index > 0) listOf(" • ") else emptyList()) + part }.joinToString(",") { "{\"text\":\"$it\"}" }}]},"lengthText":{"runs":[{"text":"$duration"}]},"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}"""

    private fun panel(contents: String) = """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[$contents]}}}}}}]}}}}}"""
    private fun panelContents(renderer: String) = panel(renderer).substringAfter("\"contents\":").dropLast(1)
}
