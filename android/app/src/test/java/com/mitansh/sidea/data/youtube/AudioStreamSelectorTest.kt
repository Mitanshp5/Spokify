package com.mitansh.sidea.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Fixture-based tests for picking a genuine audio-only direct stream.
 *
 * These use real extractor stream objects (no network); the URLs are fixtures, not live evidence.
 */
class AudioStreamSelectorTest {

    @Test
    fun `decodes URL-safe unpadded SABR config without accepting malformed data`() {
        assertEquals(listOf(0xfb, 0xff), decodeUstreamerConfig("-_8")?.map { it.toInt() and 0xff })
        assertEquals(null, decodeUstreamerConfig("not base64"))
    }

    @Test
    fun `picks the highest bitrate audio-only stream`() {
        val selected = AudioStreamSelector.select(
            listOf(
                audio(bitrateBps = 128_000, format = MediaFormat.M4A, url = "https://rr1.googlevideo.com/a?mime=audio%2Fmp4"),
                audio(bitrateBps = 64_000, format = MediaFormat.WEBMA_OPUS, url = "https://rr1.googlevideo.com/b?mime=audio%2Fwebm"),
                audio(bitrateBps = 256_000, format = MediaFormat.M4A, url = "https://rr1.googlevideo.com/c?mime=audio%2Fmp4"),
            ),
        )

        assertEquals("https://rr1.googlevideo.com/c?mime=audio%2Fmp4", selected.url)
        assertEquals("audio/mp4", selected.mimeType)
        assertEquals(256_000, selected.bitrateBps)
    }

    @Test
    fun `prefers mp4 audio when two candidates have the same bitrate`() {
        val selected = AudioStreamSelector.select(
            listOf(
                audio(bitrateBps = 128_000, format = MediaFormat.WEBMA_OPUS, url = "https://rr1.googlevideo.com/webm"),
                audio(bitrateBps = 128_000, format = MediaFormat.M4A, url = "https://rr1.googlevideo.com/mp4"),
            ),
        )

        assertEquals("https://rr1.googlevideo.com/mp4", selected.url)
    }

    @Test
    fun `refuses a sabr audio stream that has no direct url`() {
        val sabr = audio(
            bitrateBps = 128_000,
            format = MediaFormat.M4A,
            url = "sabr-session",
            deliveryMethod = DeliveryMethod.SABR,
            isUrl = false,
        )

        assertThrows(UnsupportedAudioException::class.java) {
            AudioStreamSelector.select(listOf(sabr))
        }
    }

    @Test
    fun `refuses to treat a streaming manifest as an audio-only stream`() {
        val hls = audio(
            bitrateBps = 128_000,
            format = MediaFormat.M4A,
            url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/xyz/file/index.m3u8",
            deliveryMethod = DeliveryMethod.HLS,
        )

        assertThrows(UnsupportedAudioException::class.java) {
            AudioStreamSelector.select(listOf(hls))
        }
    }

    @Test
    fun `throws a clear error when the track exposes no audio streams`() {
        val error = assertThrows(UnsupportedAudioException::class.java) {
            AudioStreamSelector.select(emptyList())
        }

        assertEquals(true, error.message?.contains("audio-only") ?: false)
    }

    @Test
    fun `still selects a direct audio stream whose bitrate is unknown`() {
        val selected = AudioStreamSelector.select(
            listOf(audio(bitrateBps = AudioStream.UNKNOWN_BITRATE, format = MediaFormat.M4A, url = "https://rr1.googlevideo.com/unknown")),
        )

        assertEquals("https://rr1.googlevideo.com/unknown", selected.url)
    }

    private fun audio(
        bitrateBps: Int,
        format: MediaFormat,
        url: String,
        deliveryMethod: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isUrl: Boolean = true,
    ): AudioStream = AudioStream.Builder()
        .setId("itag-test")
        .setContent(url, isUrl)
        .setMediaFormat(format)
        .setDeliveryMethod(deliveryMethod)
        .setAverageBitrate(bitrateBps)
        .build()
}
