package com.mitansh.sidea.data.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.util.concurrent.TimeUnit

/**
 * Live check: can any client hand out a directly fetchable URL that serves the whole recording?
 */
class DirectAudioUrlLiveTest {
    @Before fun initialize() { NewPipe.init(SideADownloader()) }

    @Test fun `finds a directly fetchable complete audio URL`() = runBlocking<Unit> {
        assumeTrue("Set SIDEA_DIRECT_AUDIO=1", System.getenv("SIDEA_DIRECT_AUDIO") == "1")
        val videoId = System.getenv("SIDEA_DIRECT_VIDEO_ID") ?: "Kf5pXDhx5Vc"
        val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
        val api = "https://youtubei.googleapis.com/youtubei/v1/"

        val profiles = listOf(
            Triple("ANDROID_VR", "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
            Triple("IOS", "20.10.4", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"),
            Triple("ANDROID", "21.03.36", "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"),
        )

        for ((name, version, ua) in profiles) {
            val client = JsonObject().apply {
                put("clientName", name); put("clientVersion", version); put("hl", "en"); put("gl", "US")
                put("utcOffsetMinutes", 0); put("clientScreen", "WATCH")
                if (name.startsWith("IOS")) {
                    put("platform", "MOBILE"); put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82"); put("androidSdkVersion", 30)
                } else {
                    put("platform", "MOBILE"); put("osName", "Android")
                    put("osVersion", "15"); put("androidSdkVersion", 35)
                }
            }
            val context = JsonObject().apply {
                put("client", client)
                put("request", JsonObject().apply { put("useSsl", true) })
                put("user", JsonObject().apply { put("lockedSafetyMode", false) })
            }
            fun post(endpoint: String, body: JsonObject): JsonObject {
                val request = Request.Builder().url(api + endpoint)
                    .header("User-Agent", ua).header("Accept", "application/json")
                    .header("X-Goog-Api-Format-Version", "2")
                    .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    return JsonParser.`object`().from(response.body.string()) as JsonObject
                }
            }
            val cpn = "AbCdEfGhIjKlMnOp"
            val player = post("reel/reel_item_watch?prettyPrint=false&id=$videoId", JsonObject().apply {
                put("context", context)
                put("playerRequest", JsonObject().apply {
                    put("videoId", videoId); put("cpn", cpn)
                    put("contentCheckOk", true); put("racyCheckOk", true)
                })
                put("disablePlayerResponse", false)
            }).getObject("playerResponse")!!
            val streaming = player.getObject("streamingData")!!
            val formats = streaming.getArray("adaptiveFormats")!!.map { it as JsonObject } +
                (streaming.getArray("formats")?.map { it as JsonObject } ?: emptyList())

            for (format in formats) {
                var url = format.getString("url") ?: continue
                val mime = format.getString("mimeType") ?: continue
                if (!mime.startsWith("audio/") && !mime.contains("mp4a")) continue
                val hadN = url.contains("&n=") || url.contains("?n=")
                if (hadN) {
                    runCatching { url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url) }
                }
                // The client nonce must match the one used for the player request.
                val parts = url.toHttpUrlOrNull()
                val withCpn = parts?.newBuilder()?.setQueryParameter("cpn", cpn)?.build()?.toString() ?: url
                val result = fetch(http, withCpn, ua)
                println("DIRECT_AUDIO client=$name itag=${format.getInt("itag")} mime=$mime " +
                    "hadN=$hadN deobfuscated=${url != withCpn} -> $result")
            }
        }
    }

    private fun fetch(http: OkHttpClient, url: String, ua: String): String = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Encoding", "identity").build()).execute().use { response ->
            val bytes = response.body.byteStream().readBytes()
            "http=${response.code} received=${bytes.size}"
        }
    } catch (error: Exception) {
        "error=${error::class.java.simpleName}"
    }
}
