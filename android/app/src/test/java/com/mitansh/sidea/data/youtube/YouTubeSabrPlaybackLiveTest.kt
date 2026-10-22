package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import com.mitansh.sidea.playback.sabr.SabrMessages
import com.mitansh.sidea.playback.sabr.SabrPart
import com.mitansh.sidea.playback.sabr.SabrSegmentAssembler
import com.mitansh.sidea.playback.sabr.SabrStreamSpec
import com.mitansh.sidea.playback.sabr.UmpReader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import java.io.ByteArrayInputStream

class YouTubeSabrPlaybackLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `live SABR song resolves to custom audio transport URI`() = runBlocking {
        assumeTrue("Set SIDEA_SABR_LIVE=1 for bounded online probe", System.getenv("SIDEA_SABR_LIVE") == "1")
        val uri = YouTubeCatalog().resolve(Track("5NV6Rdv1a3I", "Get Lucky", "Daft Punk", 248_000L))
        assertTrue(uri.startsWith("sidea-sabr://s/"))
        val spec = requireNotNull(SabrStreamSpec.parse(uri))
        var audioBytes = 0
        val downloader = SideADownloader()
        for (requestNumber in 0L..2L) {
            val body = SabrMessages.playbackRequest(
                ustreamerConfig = spec.ustreamerConfig,
                playerTimeMs = 0L,
                audioFormat = spec.format,
                initializedFormats = if (requestNumber == 0L) emptyList() else listOf(spec.format),
                clientName = spec.clientName,
                clientVersion = spec.clientVersion,
            )
            val request = ExtractorRequest.newBuilder()
                .post(com.mitansh.sidea.playback.sabr.sabrRequestEndpoint(spec.endpointUrl, requestNumber), body)
                .setHeader("Content-Type", "application/x-protobuf")
                .setHeader("Accept-Encoding", "identity")
                .setHeader("User-Agent", spec.userAgent)
                .build()
            val response = downloader.execute(request)
            assertTrue("SABR upstream HTTP ${response.responseCode()}", response.responseCode() in 200..299)
            val assembler = SabrSegmentAssembler(spec.format.itag)
            val ump = UmpReader(ByteArrayInputStream(response.rawResponseBody()))
            val initHeaderIds = mutableSetOf<Int>()
            var audioPosition = 0L
            while (ump.next() && audioBytes == 0) {
                when (ump.partType) {
                    SabrPart.MEDIA_HEADER -> {
                        val header = SabrMessages.parseMediaHeader(ump.partPayload, ump.partLength)
                        assembler.onMediaHeader(header)
                        if (header.isInitSegment) {
                            initHeaderIds += header.headerId
                            audioPosition = header.contentLength
                        }
                    }
                    SabrPart.MEDIA -> {
                        val headerId = ump.partPayload[0].toInt() and 0xff
                        if (headerId !in initHeaderIds) {
                            val window = assembler.onMedia(headerId, ump.partLength - 1, audioPosition)
                            if (window != null) audioBytes = window.length
                        }
                    }
                }
            }
            if (audioBytes > 0) break
        }
        assertTrue("SABR returned no selected audio media bytes after 3 bounded requests", audioBytes > 0)
        println("SABR_LIVE receivedAudioBytes=$audioBytes selectedItag=${spec.format.itag}")
    }
}
