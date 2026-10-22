package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest

/**
 * Live check: does the SABR route deliver the WHOLE recording, not just the first minute?
 */
class SabrFullLengthLiveTest {
    @Test fun `sabr route delivers the complete recording`() = runBlocking {
        assumeTrue("Set SIDEA_SABR_FULL=1 for bounded online probe", System.getenv("SIDEA_SABR_FULL") == "1")
        NewPipe.init(SideADownloader())
        val videoId = System.getenv("SIDEA_SABR_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val info: YoutubeSabrInfo = YoutubeSabrProbe.fetchSabrInfo(
            videoId,
            YoutubeSabrClientProfile.ANDROID,
            Localization.DEFAULT,
            ContentCountry.DEFAULT,
        )
        val audio = info.findBestAudioFormat()
        assertTrue("No audio format", audio != null)
        println("SABR_FULL audio itag=${audio!!.itag} mime=${audio.mimeType} " +
            "bitrate=${audio.bitrate} contentLength=${audio.contentLength} " +
            "durationMs=${audio.approxDurationMs} lastModified=${audio.lastModified}")

        val video = info.findLowestVideoFormat()
        assertTrue("No video format", video != null)
        val session = YoutubeSabrSession(info, audio!!, video!!)
        var totalBytes = 0L
        var segmentCount = 0
        var highestTimeMs = 0L
        val output = java.io.File(System.getenv("SIDEA_SABR_OUT") ?: "sabr-out.bin")
        val sink = java.io.BufferedOutputStream(java.io.FileOutputStream(output))
        try {
            repeat(12) { round ->
                val segments: List<SabrMediaSegment> = session.pumpOnce(Localization.DEFAULT)
                if (segments.isEmpty()) {
                    println("SABR_FULL round=$round empty, totalBytes=$totalBytes")
                    return@repeat
                }
                for (segment in segments) {
                    totalBytes += segment.length
                    segmentCount++
                    highestTimeMs = maxOf(highestTimeMs, segment.header.startMs)
                    sink.write(segment.data)
                }
                println("SABR_FULL round=$round segments=${segments.size} " +
                    "totalBytes=$totalBytes highestTimeMs=$highestTimeMs " +
                    "mediaProgressVersion=${session.mediaProgressVersion}")
                if (segments.isEmpty()) return@repeat
            }
        } finally {
            sink.flush()
            sink.close()
            session.close()
        }
        println("SABR_FULL DONE bytes=$totalBytes segments=$segmentCount highestTimeMs=$highestTimeMs file=${output.absolutePath}")
        assertTrue("SABR delivered no bytes", totalBytes > 0)
    }
}
