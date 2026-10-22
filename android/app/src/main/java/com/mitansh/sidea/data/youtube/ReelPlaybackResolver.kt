// SPDX-License-Identifier: GPL-3.0-or-later
// Android Reel flow adapted from LUC4N3X and contributors; see THIRD_PARTY_NOTICES.md.
package com.mitansh.sidea.data.youtube

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.playback.sabr.SabrStreamSpec
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ReelPlaybackResolver {
    private const val API = "https://youtubei.googleapis.com/youtubei/v1/"
    private const val VERSION = "21.03.36"
    private const val USER_AGENT = "com.google.android.youtube/$VERSION (Linux; U; Android 15; US) gzip"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024L
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val random = SecureRandom()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(track: Track): PlaybackAudio {
        require(videoIdPattern.matches(track.id)) { "Invalid video ID" }
        val visitorResponse = post("visitor_id?prettyPrint=false", json("context" to context("")))
        val visitor = visitorResponse.getObject("responseContext")?.getString("visitorData")
            ?.takeIf { it.isNotBlank() && it.length <= 8192 }
            ?: throw IOException("Playback visitor context is unavailable")
        val cpn = nonce(12)
        val response = post(
            "reel/reel_item_watch?prettyPrint=false&t=${nonce(9)}&id=${track.id}&\$fields=playerResponse",
            requestBody(track.id, visitor, cpn),
        ).getObject("playerResponse") ?: throw IOException("Playback response is missing")
        val format = selectFormat(response)
            ?: throw UnsupportedAudioException("No complete playable stream is available for this recording")
        var url = format.getString("url")
        if (!url.toHttpUrl().queryParameter("n").isNullOrBlank()) {
            url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(track.id, url)
        }
        val uri = url.toHttpUrl().newBuilder().setQueryParameter("cpn", cpn).build().toString()
        if (!SabrStreamSpec.isAllowedEndpoint(uri)) throw IOException("Untrusted media origin")
        return PlaybackAudio(
            uri,
            format.getString("mimeType").substringBefore(';'),
            format.getInt("bitrate"),
            false,
            com.mitansh.sidea.core.StreamUrlExpiry.expiryEpochMs(uri),
        )
    }

    internal fun requestBody(videoId: String, visitor: String, cpn: String): JsonObject {
        require(videoIdPattern.matches(videoId)) { "Invalid video ID" }
        require(Regex("[A-Za-z0-9_-]{16}").matches(cpn)) { "Invalid playback nonce" }
        return json(
            "context" to context(visitor),
            "playerRequest" to json("videoId" to videoId, "cpn" to cpn, "contentCheckOk" to true, "racyCheckOk" to true),
            "disablePlayerResponse" to false,
        )
    }

    private fun context(visitor: String): JsonObject = json(
        "client" to json(
            "clientName" to "ANDROID", "clientVersion" to VERSION, "clientScreen" to "WATCH",
            "platform" to "MOBILE", "osName" to "Android", "osVersion" to "16", "androidSdkVersion" to 36,
            "hl" to "en", "gl" to "US", "utcOffsetMinutes" to 0,
        ).apply { if (visitor.isNotBlank()) put("visitorData", visitor) },
        "request" to json("internalExperimentFlags" to JsonArray(), "useSsl" to true),
        "user" to json("lockedSafetyMode" to false),
    )

    private fun json(vararg fields: Pair<String, Any>) = JsonObject().apply {
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun nonce(bytes: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(bytes).also(random::nextBytes))

    private suspend fun post(endpoint: String, body: JsonObject): JsonObject = suspendCancellableCoroutine { result ->
        val request = Request.Builder().url(API + endpoint)
            .header("User-Agent", USER_AGENT).header("Accept", "application/json")
            .header("X-Goog-Api-Format-Version", "2")
            .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (result.isActive) result.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val parsed = response.use {
                        if (!it.isSuccessful) throw PlaybackHttpException(it.code)
                        val source = it.body.source()
                        source.request(MAX_JSON_BYTES + 1)
                        if (source.buffer.size > MAX_JSON_BYTES) throw IOException("Playback response is too large")
                        JsonParser.`object`().from(source.readUtf8())
                    }
                    if (result.isActive) result.resume(parsed)
                } catch (error: Exception) {
                    if (result.isActive) result.resumeWithException(error)
                }
            }
        })
    }

    internal fun selectFormat(response: JsonObject): JsonObject? {
        if (response.getObject("playabilityStatus")?.getString("status") != "OK") return null
        val streaming = response.getObject("streamingData") ?: return null
        fun valid(format: JsonObject): Boolean =
            SabrStreamSpec.isAllowedEndpoint(format.getString("url").orEmpty())
        val audio = streaming.getArray("adaptiveFormats").orEmpty().filterIsInstance<JsonObject>()
            .filter { valid(it) && it.getString("mimeType").orEmpty().startsWith("audio/") }
            .maxByOrNull { it.getInt("bitrate") }
        if (audio != null) return audio
        return streaming.getArray("formats").orEmpty().filterIsInstance<JsonObject>()
            .filter {
                val mime = it.getString("mimeType").orEmpty()
                valid(it) && mime.startsWith("video/mp4") && mime.contains("mp4a", ignoreCase = true) &&
                    (it.getInt("audioChannels") > 0 || !it.getString("audioQuality").isNullOrBlank())
            }
            .minWithOrNull(compareBy<JsonObject>(
                { it.getInt("height").takeIf { height -> height > 0 } ?: Int.MAX_VALUE },
                { it.getInt("bitrate") },
            ))
    }
}

/** Preserve status through resolver wrapping without exposing credential-bearing URLs. */
class PlaybackHttpException(val status: Int) : IOException("Playback service HTTP $status")
