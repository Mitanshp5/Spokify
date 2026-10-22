package com.mitansh.sidea.data.youtube

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import com.mitansh.sidea.playback.sabr.SabrFormatId
import com.mitansh.sidea.playback.sabr.SabrStreamSpec
import java.util.Base64

/** A genuine audio-only stream: a direct URL to audio bytes, never a page or a manifest. */
data class SelectedAudio(
    val url: String,
    val mimeType: String?,
    val bitrateBps: Int,
)

data class PlaybackAudio(
    val uri: String,
    val mimeType: String?,
    val bitrateBps: Int,
    val sabr: Boolean,
    val expiresAtEpochMs: Long? = null,
)

/** Raised when a track cannot be played without transcoding video or guessing a URL. */
class UnsupportedAudioException(message: String) : Exception(message)

object AudioStreamSelector {

    /** Progressive HTTP and DASH adaptive entries both point at complete, directly fetchable audio. */
    private val DIRECT_DELIVERIES = setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.DASH)

    fun select(streams: List<AudioStream>): SelectedAudio {
        val candidates = directCandidates(streams)
        if (candidates.isEmpty()) {
            throw UnsupportedAudioException(
                "No audio-only stream with a direct URL is available for this track",
            )
        }

        val best = candidates.maxWith(
            compareBy<AudioStream>({ bitrateOf(it) }, { if (it.format?.mimeType == MP4_MIME) 1 else 0 }),
        )
        return SelectedAudio(best.content, best.format?.mimeType, bitrateOf(best))
    }

    /** Direct URL audio stays preferred; SABR is represented by a validated custom descriptor. */
    fun selectForPlayback(streams: List<AudioStream>, durationMs: Long): PlaybackAudio {
        val direct = directCandidates(streams)
        if (direct.isNotEmpty()) {
            val selected = select(direct)
            return PlaybackAudio(selected.url, selected.mimeType, selected.bitrateBps, sabr = false)
        }

        val candidates = streams.mapNotNull { stream ->
            if (stream.deliveryMethod != DeliveryMethod.SABR) return@mapNotNull null
            val info = stream.deliveryMethodInfo as? YoutubeSabrInfo ?: return@mapNotNull null
            val itag = stream.itagItem?.id ?: return@mapNotNull null
            val format = info.formats.firstOrNull { it.itag == itag && it.isAudio() }
                ?: return@mapNotNull null
            val endpoint = info.serverAbrStreamingUrl?.takeIf { SabrStreamSpec.isAllowedEndpoint(it) }
                ?: return@mapNotNull null
            val encodedConfig = info.videoPlaybackUstreamerConfig?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val config = decodeUstreamerConfig(encodedConfig) ?: return@mapNotNull null
            val length = format.contentLength.takeIf { it > 0L } ?: return@mapNotNull null
            val duration = format.approxDurationMs.takeIf { it > 0L } ?: durationMs
                .takeIf { it > 0L } ?: return@mapNotNull null
            val profile = info.profile
            val userAgent = profile.userAgent?.takeIf { it.isNotBlank() }
                ?: YoutubeParsingHelper.WEB_USER_AGENT.takeIf { profile.clientName == "WEB" }
                ?: return@mapNotNull null
            val spec = SabrStreamSpec(
                endpointUrl = endpoint,
                ustreamerConfig = config,
                format = SabrFormatId(format.itag, format.lastModified),
                contentLength = length,
                durationMs = duration,
                clientName = profile.clientId.toIntOrNull() ?: return@mapNotNull null,
                clientVersion = info.clientVersion,
                userAgent = userAgent,
            )
            PlaybackAudio(spec.toUri(), format.mimeType, format.bitrate, sabr = true)
        }
        return candidates.maxByOrNull { it.bitrateBps }
            ?: throw UnsupportedAudioException("No supported direct or audio-only SABR stream is available")
    }

    private fun directCandidates(streams: List<AudioStream>) = streams.filter {
        it.isUrl && it.content.startsWith("https://") && it.deliveryMethod in DIRECT_DELIVERIES
    }

    private fun bitrateOf(stream: AudioStream): Int =
        if (stream.averageBitrate > 0) stream.averageBitrate else stream.bitrate

    private const val MP4_MIME = "audio/mp4"
}

internal fun decodeUstreamerConfig(encoded: String): ByteArray? =
    (runCatching { Base64.getDecoder().decode(encoded) }
        .recoverCatching { Base64.getUrlDecoder().decode(encoded) }
        .getOrNull())?.takeIf { it.isNotEmpty() }
