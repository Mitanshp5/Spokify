package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class SongRadioRepository(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun fetch(seed: Track, continuation: String? = null): RadioPage = withContext(Dispatchers.IO) {
        require(seed.id.isNotBlank())
        require(seed.id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val payload = """{"enablePersistentPlaylistPanel":true,"isAudioOnly":true,"tunerSettingValue":"AUTOMIX_SETTING_NORMAL","playlistId":"RDAMVM${seed.id}","videoId":"${seed.id}","params":"wAEB","context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260423.01.00","hl":"en","gl":"US"}}}"""
        val encoded = continuation?.takeIf(String::isNotBlank)?.let { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
        val url = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false" + (encoded?.let { "&ctoken=$it&continuation=$it" } ?: "")
        val request = Request.Builder().url(url).post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json").header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/").header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", "1.20260423.01.00").build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) throw SongRadioRequestException("HTTP ${response.code}")
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw SongRadioRequestException("response exceeds size limit")
            val source = body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw SongRadioRequestException("response exceeds size limit")
            val bytes = source.readByteArray()
            SongRadioParser.parse(bytes.toString(Charsets.UTF_8), seed)
        }
    }

    companion object { private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
}

class SongRadioRequestException(message: String) : IllegalStateException("Song radio request failed: $message")
