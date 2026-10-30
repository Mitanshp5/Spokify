package com.mitansh.sidea.data.spotify

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SpotifyMatchingRobustnessTest {
    private suspend fun match(title: String, artist: String, vararg candidates: Track, duration: Long = 200000): SpotifyTrackMatch =
        matchSpotifyPlaylist(SpotifyPlaylistPreview("id", "sample", listOf(SpotifySourceTrack(0, title, artist, duration, null)), SpotifyCompleteness.COMPLETE)) { candidates.toList() }.matches.single()

    @Test fun primaryPerformerIsAWholeSpotifyCredit() = runBlocking {
        val song = Track("BA37wV6e7IE", "Malang Sajna", "Sachet Tandon", 160000)
        assertEquals(song, match("Malang Sajna", "Sachet Tandon, Parampara Tandon, Kumaar, Sachet-Parampara", song, duration = 159108).selected)
        assertEquals(SpotifyMatchStatus.MISSING, match("Malang Sajna", "Sachet Tandon, Parampara Tandon", song.copy(artist = "Tandon")).status)
        assertEquals(SpotifyMatchStatus.MISSING, match("Malang Sajna", "Sachet Tandon", song.copy(artist = "Sachet Kapoor")).status)
    }

    @Test fun filmAndPresentationLabelsDoNotChangeRecordingIdentity() = runBlocking {
        val cases = listOf(
            Triple("Woh Din", "Woh Din [From \"Chhichhore\"]", "Arijit Singh"),
            Triple("Nain Matakka - From \"Baby John\"", "Nain Matakka (From \"Baby John\")", "Thaman S"),
            Triple("Dwarika No Naath", "Dwarika No Naath ( From Laalo )", "Jaysinh Gadhavi"),
            Triple("Destiny - Mann Atkeya", "Destiny - Mann Atkeya (From \"Dhurandhar The Revenge\")", "Shashwat Sachdev"),
            Triple("Café", "Cafe (Official Lyric Video)", "Beyoncé"),
            Triple("Song", "Song - Official Audio", "Artist"),
        )
        for ((title, label, artist) in cases) {
            assertEquals(title, SpotifyMatchStatus.AUTO, match(title, artist, Track("id", label, artist, 201000)).status)
        }
        assertEquals(SpotifyMatchStatus.MISSING, match("Song", "Artist", Track("id", "Another Song", "Artist", 200000)).status)
    }

    @Test fun implausibleDifferingCreditDoesNotVetoExactRecording() = runBlocking {
        val exact = Track("exact", "Get Lucky", "Daft Punk", 200000)
        val far = exact.copy(id = "far", artist = "Daft Punk & Pharrell Williams", durationMs = 350000)
        assertEquals(exact, match("Get Lucky", "Daft Punk", exact, far).selected)
        assertEquals(SpotifyMatchStatus.REVIEW, match("Get Lucky", "Daft Punk", exact, far.copy(durationMs = 200000)).status)
        assertEquals(SpotifyMatchStatus.REVIEW, match("Get Lucky", "Daft Punk", exact, exact.copy(id = "tie")).status)
    }

    @Test fun durationAndVersionEvidenceCannotBeBypassedByTitleAgreement() = runBlocking {
        for (suffix in listOf("Live", "Remix", "Acoustic", "Cover", "Karaoke", "Instrumental", "Reprise", "Sped Up", "Slowed + Reverb", "Lofi", "Unplugged")) {
            assertEquals(suffix, SpotifyMatchStatus.MISSING, match("Song", "Artist", Track("id", "Song ($suffix)", "Artist", 200000)).status)
        }
        assertEquals(SpotifyMatchStatus.MISSING, match("Song", "Artist", Track("id", "Song", "Artist", 350000)).status)
        assertEquals(SpotifyMatchStatus.REVIEW, match("Song", "Artist", Track("id", "Song", "Artist", 0)).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Song", "Artist", Track("id", "Song", "Artist", 209000)).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Darling - Instrumental", "LeoKarlo", Track("id", "Darling - Instrumental", "LeoKarlo", 201000)).status)
    }

    @Test fun unexplainedCreditAliasesAndDuplicateRecordingsStayForReview() = runBlocking {
        assertEquals(SpotifyMatchStatus.REVIEW, match("Sakhiyan2.0", "Maninder Buttar, Zahrah S Khan, Tanishk Bagchi",
            Track("Q_FKqShcFS8", "Sakhiyan2.0", "Maninder Buttar, Zara Khan", 178000), duration = 177694).status)
        val song = Track("K1FCbwYo8MI", "Yaar Bina Chain Kanha Ray (From \"Saaheb\")", "Bappi Lahiri", 273000)
        assertEquals(SpotifyMatchStatus.REVIEW, match("Yaar Bina Chain Kanha Ray", "Bappi Lahiri, S. Janaki",
            song, song.copy(id = "vxxkzHqU5zY", title = "Yaar Bina Chain Kanha Ray", durationMs = 274000), duration = 273551).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Café", "Beyoncé", Track("id", "Cafe", "Beyonce", 201000)).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Maskara", "A.R. Rahman, Vedang Raina", Track("id", "Maskara", "A. R. Rahman", 201000)).status)
    }

    @Test fun matchingPreservesOrderingProgressAndCancellation() = runBlocking {
        val rows = listOf("First", "Second", "First").mapIndexed { i, title -> SpotifySourceTrack(i, title, "Artist", 200000, null) }
        val playlist = SpotifyPlaylistPreview("id", "sample", rows, SpotifyCompleteness.COMPLETE)
        val progress = mutableListOf<Int>()
        val result = matchSpotifyPlaylist(playlist, { done, total -> assertEquals(3, total); progress += done }) {
            listOf(Track(it, it.removeSuffix(" Artist"), "Artist", 200000))
        }
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(rows.map { it.title }, result.selectedTracks().map { it?.title })
        try {
            matchSpotifyPlaylist(playlist) { throw CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun bracketedFeaturesUseWholeSourceCreditsWithoutChangingVersionIdentity() = runBlocking {
        data class Case(val source: String, val artist: String, val duration: Long, val candidate: String, val performer: String, val length: Long)
        val cases = listOf(
            Case("Jhol - Acoustic (feat. Asfand)", "Maanu, Annural Khalid, Abdullah Siddiqui, Asfand", 277600, "Jhol (Acoustic)", "Maanu", 278000),
            Case("RAMO RE", "Jigardan Gadhavi, Kavya Limaye", 228588, "RAMO RE (feat. Kavya Limaye)", "Jigardan Gadhavi", 229000),
            Case("Main Aur Tu", "Shashwat Sachdev, Jasmine Sandlas, Reble", 210267, "Main Aur Tu (From \"Dhurandhar The Revenge\") (Feat. Reble) (feat. Reble)", "Shashwat Sachdev", 211000),
            Case("Baby", "Justin Bieber, Ludacris", 214240, "Baby (feat. Ludacris)", "Justin Bieber", 215000),
            Case("Closer", "The Chainsmokers, Halsey", 244960, "Closer (feat. Halsey)", "The Chainsmokers", 246000),
            Case("Dreamers [Music from the FIFA World Cup Qatar 2022 Official Soundtrack]", "Jung Kook, BTS, FIFA Sound", 201391,
                "Dreamers (Music from the FIFA World Cup Qatar 2022 Official Soundtrack) (feat. FIFA Sound)", "Jung Kook", 202000),
        )
        for (case in cases) {
            assertEquals(case.source, SpotifyMatchStatus.AUTO, match(case.source, case.artist,
                Track("id", case.candidate, case.performer, case.length), duration = case.duration).status)
        }
        for (label in listOf("feat.", "featuring", "with")) {
            assertEquals(label, SpotifyMatchStatus.AUTO, match("Baby", "Justin Bieber, Ludacris",
                Track("id", "Baby [$label Ludacris]", "Justin Bieber", 215000), duration = 214240).status)
        }
        assertEquals(SpotifyMatchStatus.REVIEW, match("Hall of Fame", "The Script",
            Track("id", "Hall of Fame (feat. will.i.am)", "The Script", 202000), duration = 201093).status)
        assertEquals(SpotifyMatchStatus.REVIEW, match("Baby", "Justin Bieber, Ludacris",
            Track("id", "Baby (feat. Chris)", "Justin Bieber", 215000), duration = 214240).status)
        assertEquals(SpotifyMatchStatus.MISSING, match("Baby", "Justin Bieber, Ludacris",
            Track("id", "Baby (feat. Ludacris)", "Unrelated Band", 215000), duration = 214240).status)
        assertEquals(SpotifyMatchStatus.MISSING, match("Jhol - Acoustic (feat. Asfand)", "Maanu, Asfand",
            Track("id", "Jhol", "Maanu", 278000), duration = 277600).status)
        val allStars = Track("one", "All The Stars (From \"Black Panther: The Album\")", "Kendrick Lamar", 233000)
        assertEquals(SpotifyMatchStatus.AUTO, match("All The Stars (with SZA) - From \"Black Panther: The Album\"", "Kendrick Lamar, SZA",
            allStars, duration = 232186).status)
        assertEquals(SpotifyMatchStatus.REVIEW, match("All The Stars (with SZA) - From \"Black Panther: The Album\"", "Kendrick Lamar, SZA",
            allStars, allStars.copy(id = "two"), duration = 232186).status)
    }

    @Test fun filmMetadataPreservesExplicitLanguageAndVersionSuffixes() = runBlocking {
        val source = "Manorath Jeev (From \"Laalo\")"
        val artist = "Kirtidan Gadhvi, Smmit Jay"
        for (label in listOf("From \"Laalo\"Hindi", "From \"Laalo\" Tamil", "Movie: \"Laalo\" Telugu", "Movie: Laalo Hindi", "Movie: Laalo Tamil", "From \"Laalo\" Live")) {
            assertEquals(label, SpotifyMatchStatus.MISSING, match(source, artist,
                Track("id", "Manorath Jeev ($label)", "Kirtidan Gadhvi", 259000), duration = 257556).status)
        }
        assertEquals(SpotifyMatchStatus.MISSING, match(source, artist,
            Track("id", "Manorath Jeev - From \"Laalo\" Hindi", "Kirtidan Gadhvi", 259000), duration = 257556).status)
        assertEquals(SpotifyMatchStatus.AUTO, match(source, artist,
            Track("id", "Manorath Jeev (From \"Laalo\")", "Kirtidan Gadhvi", 259000), duration = 257556).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Tum Ho Toh (From \"Saiyaara\")", "Vishal Mishra, Hansika Pareek, Raj Shekhar",
            Track("id", "Tum Ho Toh (Movie: Saiyaara)", "Vishal Mishra", 319000), duration = 318217).status)
    }

    @Test fun indicVowelsRemainIdentityEvidenceWhileLatinAccentsNormalize() = runBlocking {
        assertEquals(SpotifyMatchStatus.MISSING, match("दिन", "Artist", Track("id", "दीन", "Artist", 200000)).status)
        assertEquals(SpotifyMatchStatus.MISSING, match("Song", "नील", Track("id", "Song", "नल", 200000)).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("दिन", "नील", Track("id", "दिन", "नील", 200000)).status)
        assertEquals(SpotifyMatchStatus.AUTO, match("Café", "Beyoncé", Track("id", "Cafe", "Beyonce", 200000)).status)
    }
}
