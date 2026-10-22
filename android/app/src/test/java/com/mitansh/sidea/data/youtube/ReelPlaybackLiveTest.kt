package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import com.mitansh.sidea.playback.sabr.SabrStreamSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReelPlaybackLiveTest {
    @Test fun `actual catalog resolves the same full recording without the one minute SABR route`() = runBlocking {
        assumeTrue(System.getenv("SIDEA_REEL_LIVE") == "1")
        YouTubeCatalog.initialize()
        val videoId = System.getenv("SIDEA_REEL_VIDEO_ID") ?: "4D7u5KF7SP8"
        val audio = YouTubeCatalog().resolveAudio(Track(videoId, "Live playback probe", "Source probe"))
        assertFalse("SABR startup is not a complete playback route", audio.sabr)
        assertTrue("Expected a real validated media origin", SabrStreamSpec.isAllowedEndpoint(audio.uri))
        assertTrue(audio.mimeType in listOf("audio/mp4", "audio/webm", "video/mp4"))
        val output = System.getenv("SIDEA_REEL_AUDIO_OUTPUT")
        var bytes = 0L
        if (output != null) {
            val client = okhttp3.OkHttpClient.Builder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(okhttp3.Request.Builder().url(audio.uri).build()).execute().use { response ->
                assertTrue("Media HTTP ${response.code}", response.isSuccessful)
                val file = java.io.File(output)
                file.parentFile?.mkdirs()
                response.body.byteStream().use { input -> file.outputStream().use { sink ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytes += count
                        assertTrue("Bounded recording probe", bytes <= 12L * 1024 * 1024)
                        sink.write(buffer, 0, count)
                    }
                } }
                assertTrue("Expected media bytes", bytes > 1024)
                if (audio.mimeType?.contains("mp4") == true) {
                    java.io.RandomAccessFile(file, "r").use { media ->
                        var foundMedia = false
                        while (media.filePointer < media.length()) {
                            val start = media.filePointer
                            assertTrue("Truncated MP4 atom header", media.length() - start >= 8)
                            val size32 = media.readInt().toLong() and 0xffffffffL
                            val kind = media.readInt()
                            val size = when (size32) {
                                0L -> media.length() - start
                                1L -> media.readLong()
                                else -> size32
                            }
                            val header = media.filePointer - start
                            assertTrue("MP4 atom declares missing media bytes", size >= header && size <= media.length() - start)
                            if (kind == 0x6d646174 && size > header) foundMedia = true
                            media.seek(start + size)
                        }
                        assertTrue("MP4 contains headers without media samples", foundMedia)
                    }
                }
            }
        }
        println("REEL_PRODUCTION_ROUTE id=$videoId supported=true mime=${audio.mimeType} sabr=${audio.sabr} fetchedBytes=$bytes")
    }
}
