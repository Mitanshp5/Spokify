package com.mitansh.sidea.data.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Reduced response fixtures, not remote-service evidence. */
class ReelPlaybackResolverTest {
    private fun response(adaptive: String = "[]", muxed: String = "[]"): JsonObject =
        JsonParser.`object`().from("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":$adaptive,"formats":$muxed}}""")

    @Test fun `prefers available direct audio over a muxed stream`() {
        val selected = ReelPlaybackResolver.selectFormat(response(
            adaptive = """[{"itag":140,"mimeType":"audio/mp4","url":"https://rr1.googlevideo.com/audio","bitrate":128000}]""",
            muxed = """[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a.40.2\"","url":"https://rr1.googlevideo.com/video","height":360,"audioChannels":2}]"""
        ))
        assertEquals(140, selected!!.getInt("itag"))
    }

    @Test fun `does not substitute a video-only format or unsafe origin`() {
        val noAudio = """[{"itag":137,"mimeType":"video/mp4; codecs=\"avc1\"","url":"https://rr1.googlevideo.com/video","height":1080}]"""
        assertNull(ReelPlaybackResolver.selectFormat(response(muxed = noAudio)))
        for (url in listOf("http://rr1.googlevideo.com/media", "https://googlevideo.com.evil.test/media", "https://user@rr1.googlevideo.com/media", "https://127.0.0.1/media")) {
            assertNull(ReelPlaybackResolver.selectFormat(response(adaptive =
                """[{"mimeType":"audio/mp4","url":"$url"}]""")))
        }
    }

    @Test fun `never accepts formats from a non-playable response`() {
        val denied = response(adaptive = """[{"mimeType":"audio/mp4","url":"https://rr1.googlevideo.com/audio"}]""")
        denied.getObject("playabilityStatus")["status"] = "LOGIN_REQUIRED"
        assertNull(ReelPlaybackResolver.selectFormat(denied))
        assertNull(ReelPlaybackResolver.selectFormat(response()))
    }

    @Test fun `uses the lowest available muxed resolution instead of needless video bandwidth`() {
        val formats = """[{"itag":22,"mimeType":"video/mp4; codecs=\"avc1, mp4a.40.2\"","url":"https://rr1.googlevideo.com/720","height":720,"audioChannels":2},{"itag":18,"mimeType":"video/mp4; codecs=\"avc1, mp4a.40.2\"","url":"https://rr1.googlevideo.com/360","height":360,"audioChannels":2}]"""
        assertEquals(18, ReelPlaybackResolver.selectFormat(response(muxed = formats))!!.getInt("itag"))
    }

    @Test fun `Reel request keeps the same recording visitor and generated playback nonce`() {
        val body = ReelPlaybackResolver.requestBody("4D7u5KF7SP8", "fixture-visitor", "abcdefghijklmnop")
        assertEquals("4D7u5KF7SP8", body.getObject("playerRequest")?.getString("videoId"))
        assertEquals("abcdefghijklmnop", body.getObject("playerRequest")?.getString("cpn"))
        assertEquals("fixture-visitor", body.getObject("context")?.getObject("client")?.getString("visitorData"))
        assertEquals("ANDROID", body.getObject("context")?.getObject("client")?.getString("clientName"))
        assertEquals(false, body.getBoolean("disablePlayerResponse"))
    }

    @Test fun `rejects a malformed atomic video ID before making a request`() {
        for (id in listOf("", "4D7u5KF7SP8?x=1", "https://youtube.com/watch?v=4D7u5KF7SP8", "4D7u5KF7SP")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReelPlaybackResolver.requestBody(id, "fixture-visitor", "abcdefghijklmnop")
            }
        }
    }

    @Test fun `uses complete muxed MP4 when audio entries have no URLs`() {
        val selected = ReelPlaybackResolver.selectFormat(response(
            adaptive = """[{"mimeType":"audio/webm","itag":251,"bitrate":128000}]""",
            muxed = """[{"itag":18,"mimeType":"video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"","url":"https://rr1.googlevideo.com/videoplayback?itag=18","height":360,"audioChannels":2,"bitrate":140000}]"""
        ))
        assertNotNull("A real muxed format must replace the broken SABR-only choice", selected)
        assertEquals(18, selected!!.getInt("itag"))
    }
}
