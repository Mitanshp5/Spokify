package com.mitansh.sidea.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Live check: list every recording of one song and say which serve the complete file.
 */
class SongPlayabilityLiveTest {
    @Before fun initialize() { YouTubeCatalog.initialize() }

    @Test fun `reports which recordings of a song are playable in full`() = runBlocking {
        assumeTrue("Set SIDEA_SONG_LIVE=1", System.getenv("SIDEA_SONG_LIVE") == "1")
        val query = System.getenv("SIDEA_SONG_QUERY") ?: "End of Beginning"
        val tracks = YouTubeCatalog().search(query)
        println("SONG_LIVE query=$query results=${tracks.size}")
        val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
        for (track in tracks) {
            val verdict = try {
                val audio = ReelPlaybackResolver.resolve(track)
                val request = Request.Builder().url(audio.uri).build()
                http.newCall(request).execute().use { response ->
                    val bytes = response.body.byteStream().readBytes()
                    val declared = response.header("Content-Length")?.toLongOrNull()
                    val gir = audio.uri.substringAfter('?', "").split('&')
                        .firstOrNull { it.startsWith("gir=") }
                    "http=${response.code} received=${bytes.size} declared=$declared gir=$gir"
                }
            } catch (error: Exception) {
                "error=${error::class.java.simpleName}: ${error.message?.take(80)}"
            }
            println("SONG_LIVE id=${track.id} title='${track.title}' artist='${track.artist}' -> $verdict")
        }
    }
}
