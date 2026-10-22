package com.mitansh.sidea.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Live check: which direct audio URLs does the extractor find, and do they serve the whole file?
 */
class ExtractorDirectUrlLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `lists every direct audio route for a recording`() = runBlocking {
        assumeTrue("Set SIDEA_DIRECT_LIVE=1", System.getenv("SIDEA_DIRECT_LIVE") == "1")
        val videoId = System.getenv("SIDEA_DIRECT_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streams = extractor.audioStreams
        println("DIRECT_LIVE video=$videoId title=${extractor.name} audioStreams=${streams.size}")
        for (stream in streams) {
            val content = stream.content
            val query = content.substringAfter('?', "")
            val gir = query.split('&').firstOrNull { it.startsWith("gir=") }
            val clen = query.split('&').firstOrNull { it.startsWith("clen=") }
            val hasPot = query.split('&').any { it.startsWith("pot=") }
            println("DIRECT_LIVE itag=${stream.itag} id=${stream.id} mime=${stream.format?.mimeType} " +
                "bitrate=${stream.bitrate} avgBitrate=${stream.averageBitrate} isUrl=${stream.isUrl} " +
                "delivery=${stream.deliveryMethod} gir=$gir clen=$clen pot=$hasPot " +
                "init=${stream.initStart}-${stream.initEnd} index=${stream.indexStart}-${stream.indexEnd} " +
                "content=${content.take(90)}")
        }
        val complete = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        assertTrue("No progressive audio route", complete.isNotEmpty())
    }
}
