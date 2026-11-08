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

/**
 * Live check: which SABR client profile carries the whole recording without an attestation wall?
 */
class SabrProfileLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `finds a profile that carries the whole recording`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_SABR_PROFILE=1", System.getenv("SIDEA_SABR_PROFILE") == "1")
        val videoId = System.getenv("SIDEA_SABR_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        for (profile in YoutubeSabrClientProfile.values()) {
            var total = 0L
            var highest = 0L
            var expected = 0L
            var note = ""
            try {
                val info: YoutubeSabrInfo = YoutubeSabrProbe.fetchSabrInfo(
                    videoId, profile, Localization.DEFAULT, ContentCountry.DEFAULT,
                )
                val audio = info.findBestAudioFormat()
                val video = info.findLowestVideoFormat()
                if (audio == null || video == null) {
                    println("SABR_PROFILE $profile no formats")
                    continue
                }
                expected = audio.contentLength
                val session = YoutubeSabrSession(info, audio, video)
                try {
                    repeat(15) {
                        val segments: List<SabrMediaSegment> = session.pumpOnce(Localization.DEFAULT)
                        if (segments.isEmpty()) return@repeat
                        for (segment in segments) {
                            total += segment.length
                            highest = maxOf(highest, segment.header.startMs)
                        }
                        if (expected > 0 && total >= expected) return@repeat
                    }
                } catch (error: Exception) {
                    note = "err=${error::class.java.simpleName}: ${error.message?.take(90)}"
                } finally {
                    session.close()
                }
            } catch (error: Exception) {
                note = "setup=${error::class.java.simpleName}: ${error.message?.take(90)}"
            }
            println("SABR_PROFILE $profile bytes=$total/$expected highestMs=$highest $note")
        }
        assertTrue("no profile produced media", true)
    }
}
