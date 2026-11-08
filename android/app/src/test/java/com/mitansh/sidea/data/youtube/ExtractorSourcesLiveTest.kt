package com.mitansh.sidea.data.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import java.util.concurrent.TimeUnit

/**
 * Live check: which of the extractor's many client sources serve the whole recording?
 */
class ExtractorSourcesLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `lists and probes every stream the extractor can offer`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_SOURCES_LIVE=1", System.getenv("SIDEA_SOURCES_LIVE") == "1")
        val videoId = System.getenv("SIDEA_SOURCES_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

        val audio = extractor.audioStreams
        val video = extractor.videoStreams
        val videoOnly = extractor.videoOnlyStreams
        println("SOURCES video=$videoId audio=${audio.size} video=${video.size} videoOnly=${videoOnly.size}")

        val all = audio.map { "audio" to it } + video.map { "video" to it } + videoOnly.map { "videoOnly" to it }
        for ((kind, stream) in all) {
            val content = stream.content
            if (!content.startsWith("https://")) {
                println("SOURCES kind=$kind itag=${stream.id} delivery=${stream.deliveryMethod} no-url")
                continue
            }
            val query = content.substringAfter('?', "")
            val gir = query.split('&').firstOrNull { it.startsWith("gir=") }
            val clen = query.split('&').firstOrNull { it.startsWith("clen=") }
            var verdict = "?"
            try {
                http.newCall(Request.Builder().url(content).build()).execute().use { response ->
                    val bytes = response.body.byteStream().readBytes()
                    verdict = "http=${response.code} received=${bytes.size} declared=${response.header("Content-Length")}"
                }
            } catch (error: Exception) {
                verdict = "error=${error::class.java.simpleName}"
            }
            println("SOURCES kind=$kind itag=${stream.id} mime=${stream.format?.mimeType} " +
                "delivery=${stream.deliveryMethod} gir=$gir clen=$clen -> $verdict")
        }
    }
}
