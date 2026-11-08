package com.mitansh.sidea.data.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.mitansh.sidea.playback.sabr.SabrFormatId
import com.mitansh.sidea.playback.sabr.SabrMessages
import com.mitansh.sidea.playback.sabr.SabrPart
import com.mitansh.sidea.playback.sabr.SabrSegmentAssembler
import com.mitansh.sidea.playback.sabr.UmpReader
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live check: can the app's own SABR transport carry the whole recording?
 *
 * This drives the production classes in playback/sabr exactly as SabrDataSource does, so a
 * pass here means the production route works.
 */
class SabrFullTransportLiveTest {
    @Test fun `carries the complete recording`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_SABR_TRANSPORT=1", System.getenv("SIDEA_SABR_TRANSPORT") == "1")
        val videoId = System.getenv("SIDEA_SABR_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

        // 1. The player response, exactly as the production resolver asks for it.
        val api = "https://youtubei.googleapis.com/youtubei/v1/"
        val ua = "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
        fun post(endpoint: String, body: JsonObject): JsonObject {
            val request = Request.Builder().url(api + endpoint)
                .header("User-Agent", ua).header("Accept", "application/json")
                .header("X-Goog-Api-Format-Version", "2")
                .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                return JsonParser.`object`().from(response.body.string()) as JsonObject
            }
        }
        val client = JsonObject().apply {
            put("clientName", "ANDROID"); put("clientVersion", "21.03.36")
            put("clientScreen", "WATCH"); put("platform", "MOBILE"); put("osName", "Android")
            put("osVersion", "16"); put("androidSdkVersion", 36); put("hl", "en"); put("gl", "US")
            put("utcOffsetMinutes", 0)
        }
        val context = JsonObject().apply {
            put("client", client)
            put("request", JsonObject().apply { put("internalExperimentFlags", com.grack.nanojson.JsonArray()); put("useSsl", true) })
            put("user", JsonObject().apply { put("lockedSafetyMode", false) })
        }
        val visitor = post("visitor_id?prettyPrint=false", context)
            .getObject("responseContext")?.getString("visitorData").orEmpty()
        client.put("visitorData", visitor)
        val cpn = "AbCdEfGhIjKlMnOp"
        val playerRequest = JsonObject().apply {
            put("videoId", videoId); put("cpn", cpn)
            put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val body = JsonObject().apply {
            put("context", context); put("playerRequest", playerRequest); put("disablePlayerResponse", false)
        }
        val player = post("reel/reel_item_watch?prettyPrint=false&id=$videoId", body)
            .getObject("playerResponse")!!
        val streaming = player.getObject("streamingData")!!
        val endpoint = streaming.getString("serverAbrStreamingUrl")!!
        val ustreamerB64 = player.getObject("playerConfig")!!.getObject("mediaCommonConfig")!!
            .getObject("mediaUstreamerRequestConfig")!!.getString("videoPlaybackUstreamerConfig")!!
        val ustreamer = java.util.Base64.getDecoder().decode(
            ustreamerB64.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) })

        // 2. The best audio format, as the production selector would pick it.
        val audio = streaming.getArray("adaptiveFormats")!!.map { it as JsonObject }
            .filter { it.getString("mimeType")?.startsWith("audio/") == true }
            .maxByOrNull { it.getInt("bitrate") }!!
        val itag = audio.getInt("itag")
        val lastModified = audio.getString("lastModified")!!.toLong()
        val contentLength = audio.getString("contentLength")!!.toLong()
        val durationMs = audio.getString("approxDurationMs")!!.toLong()
        println("SABR_TRANSPORT video=$videoId itag=$itag contentLength=$contentLength durationMs=$durationMs")
        val spec = SabrFormatId(itag, lastModified)

        // 3. Drive the production transport, exactly as SabrDataSource does.
        val assembler = SabrSegmentAssembler(itag)
        var position = 0L
        var received = 0L
        var initBytes = 0L
        var rounds = 0
        while (rounds < 25 && received < contentLength) {
            rounds++
            val request = SabrMessages.playbackRequest(
                ustreamerConfig = ustreamer,
                playerTimeMs = 0L,
                audioFormat = spec,
                initializedFormats = if (rounds == 1) emptyList() else listOf(spec),
                clientName = 3,
                clientVersion = "21.03.36",
            )
            val mediaRequest = Request.Builder()
                .url(endpoint + (if ('?' in endpoint) "&" else "?") + "rn=" + (rounds - 1))
                .header("Content-Type", "application/x-protobuf")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", ua)
                .post(request.toRequestBody("application/x-protobuf".toMediaType()))
                .build()
            var sabrError: String? = null
            http.newCall(mediaRequest).execute().use { response ->
                val bodyBytes = response.body.byteStream().readBytes()
                val reader = UmpReader(java.io.ByteArrayInputStream(bodyBytes))
                while (reader.next()) {
                    when (reader.partType) {
                        SabrPart.MEDIA_HEADER -> {
                            val header = SabrMessages.parseMediaHeader(reader.partPayload, reader.partLength)
                            assembler.onMediaHeader(header)
                            if (header.isInitSegment) initBytes = header.contentLength
                        }
                        SabrPart.MEDIA -> {
                            val headerId = SabrMessages.mediaHeaderId(reader.partPayload, reader.partLength)
                            val window = assembler.onMedia(
                                headerId,
                                reader.partLength - SabrSegmentAssembler.MEDIA_PAYLOAD_OFFSET,
                                position,
                            ) ?: continue
                            received += window.length
                            position += window.length
                        }
                        SabrPart.SABR_ERROR -> {
                            sabrError = String(reader.partPayload)
                        }
                    }
                }
            }
            if (sabrError != null) {
                println("SABR_TRANSPORT round=$rounds SABR_ERROR payload=$sabrError")
                break
            }
            println("SABR_TRANSPORT round=$rounds received=$received/$contentLength initBytes=$initBytes")
        }
        println("SABR_TRANSPORT DONE received=$received of $contentLength (${(100.0 * received / contentLength).toInt()}%)")
        assertTrue("SABR transport did not carry the whole recording", received >= contentLength)
    }
}
