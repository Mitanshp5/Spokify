package com.mitansh.sidea.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment
import java.io.File
import java.io.FileOutputStream

/**
 * Live check: can the SABR route carry the WHOLE recording across identity rotations?
 *
 * The 59-second wall is YouTube asking for attestation. This tries a fresh guest identity
 * whenever the server demands attestation, and keeps the bytes it already has.
 */
class SabrFullSongLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `carries the complete recording across attestation walls`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_SABR_SONG=1", System.getenv("SIDEA_SABR_SONG") == "1")
        val videoId = System.getenv("SIDEA_SABR_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val output = File(System.getenv("SIDEA_SABR_SONG_OUT") ?: "sabr-song.bin")
        val sink = FileOutputStream(output)

        var totalBytes = 0L
        var highestMs = 0L
        var rotations = 0
        var expectedLength = 0L
        var expectedDurationMs = 0L
        val collected = mutableListOf<SabrMediaSegment>()

        while (rotations < 12 && (expectedLength == 0L || totalBytes < expectedLength)) {
            rotations++
            val info: YoutubeSabrInfo = YoutubeSabrProbe.fetchSabrInfo(
                videoId, YoutubeSabrClientProfile.ANDROID, Localization.DEFAULT, ContentCountry.DEFAULT,
            )
            val audio = info.findBestAudioFormat()
            val video = info.findLowestVideoFormat()
            if (audio == null || video == null) {
                println("SABR_SONG rotation=$rotations no formats")
                break
            }
            expectedLength = audio.contentLength
            expectedDurationMs = audio.approxDurationMs
            println("SABR_SONG rotation=$rotations itag=${audio.itag} expected=$expectedLength")
            val session = YoutubeSabrSession(info, audio, video)
            try {
                repeat(10) {
                    val segments: List<SabrMediaSegment> = session.pumpOnce(Localization.DEFAULT)
                    if (segments.isEmpty()) return@repeat
                    for (segment in segments) {
                        totalBytes += segment.length
                        highestMs = maxOf(highestMs, segment.header.startMs)
                        collected += segment
                    }
                    println("SABR_SONG rotation=$rotations bytes=$totalBytes highestMs=$highestMs")
                    if (expectedLength > 0 && totalBytes >= expectedLength) return@repeat
                }
            } catch (error: Exception) {
                println("SABR_SONG rotation=$rotations error=${error::class.java.simpleName}: ${error.message?.take(120)}")
            } finally {
                session.close()
            }
        }

        // Write the media we gathered, in the order it arrived.
        for (segment in collected) {
            sink.write(segment.data)
        }
        sink.flush()
        sink.close()
        println("SABR_SONG DONE bytes=$totalBytes expected=$expectedLength " +
            "highestMs=$highestMs expectedDurationMs=$expectedDurationMs file=${output.absolutePath}")
        assertTrue("SABR did not carry the whole recording", totalBytes >= expectedLength)
    }
}
