package com.mitansh.sidea.data.lyrics

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.mitansh.sidea.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

internal fun interface LyricsSource {
    suspend fun find(query: LyricsQuery): Lyrics?
}

/** Only successful lookups are persisted. A network outage must never become a cached miss. */
internal class LyricsRepository(
    private val directory: File,
    private val source: LyricsSource = LrclibSource(),
) {
    suspend fun load(track: Track, online: Boolean): Lyrics? = withContext(Dispatchers.IO) {
        val query = LyricsQuery.from(track)
        if (query.title.isBlank() || query.artist.isBlank()) return@withContext null
        val key = MessageDigest.getInstance("SHA-256").digest(query.key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.json")
        read(file)?.let { return@withContext it }
        if (!online) throw LyricsOfflineException()
        val result = source.find(query) ?: return@withContext null
        coroutineContext.ensureActive()
        // Cache failure should not hide lyrics that were fetched successfully.
        try {
            directory.mkdirs()
            val json = JsonObject().apply {
                put("version", 1)
                put("plain", result.plainText)
                put("instrumental", result.instrumental)
                put("lrc", result.lines.joinToString("\n") { line ->
                    val minutes = line.timeMs / 60_000
                    val seconds = (line.timeMs / 1000) % 60
                    val millis = line.timeMs % 1000
                    "[%02d:%02d.%03d]%s".format(java.util.Locale.ROOT, minutes, seconds, millis, line.text)
                })
            }
            val text = JsonWriter.string(json)
            if (text.toByteArray(Charsets.UTF_8).size <= MAX_LYRICS_BYTES) {
                val temporary = File.createTempFile("lyrics-", ".tmp", directory)
                try {
                    temporary.writeText(text, Charsets.UTF_8)
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally { temporary.delete() }
                trim()
            }
        } catch (_: IOException) { }
        result
    }

    private fun read(file: File): Lyrics? = try {
        if (!file.isFile || file.length() > MAX_LYRICS_BYTES) null else {
            val json = JsonParser.`object`().from(file.readText(Charsets.UTF_8))
            if (json.getInt("version") != 1) null else {
                val result = Lyrics(json.getString("plain").orEmpty(), LrcParser.parse(json.getString("lrc")), json.getBoolean("instrumental"))
                if (!result.instrumental && result.plainText.isBlank() && !result.isSynced) null else {
                    file.setLastModified(System.currentTimeMillis())
                    result
                }
            }
        }
    } catch (_: Exception) { null }

    private fun trim() {
        val files = directory.listFiles { file -> file.extension == "json" }?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= 100 || bytes > 8 * 1024 * 1024) file.delete()
        }
    }
}

internal class LyricsOfflineException : IOException("Lyrics have not been saved for this song")

internal class LrclibSource(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : LyricsSource {
    override suspend fun find(query: LyricsQuery): Lyrics? {
        val direct = url("get", query).apply {
            if (query.durationMs > 0) addQueryParameter("duration", (query.durationMs / 1000.0).toString())
        }.build()
        get(direct)?.let { raw ->
            decode(JsonParser.`object`().from(raw), query)?.let { return it }
        }
        val body = get(url("search", query).build()) ?: return null
        return JsonParser.array().from(body).filterIsInstance<JsonObject>()
            .mapNotNull { json -> decode(json, query)?.let { json to it } }
            .sortedWith(compareByDescending<Pair<JsonObject, Lyrics>> { it.second.isSynced }
                .thenBy { abs(it.first.getDouble("duration") * 1000 - query.durationMs) })
            .firstOrNull()?.second
    }

    private fun url(endpoint: String, query: LyricsQuery) = "https://lrclib.net/api/$endpoint".toHttpUrl().newBuilder()
        .addQueryParameter("track_name", query.title).addQueryParameter("artist_name", query.artist)

    internal fun decode(json: JsonObject, query: LyricsQuery): Lyrics? {
        if (LyricsQuery.normalize(json.getString("trackName").orEmpty()) != LyricsQuery.normalize(query.title) ||
            LyricsQuery.normalize(json.getString("artistName").orEmpty()) != LyricsQuery.normalize(query.artist)) return null
        val duration = json.getDouble("duration") * 1000
        if (query.durationMs > 0 && (!duration.isFinite() || duration <= 0 || abs(duration - query.durationMs) > 3000)) return null
        val instrumental = json.getBoolean("instrumental")
        val plain = json.getString("plainLyrics").orEmpty().trim()
        val lines = if (instrumental) emptyList() else LrcParser.parse(json.getString("syncedLyrics"))
        if (!instrumental && plain.isBlank() && lines.none { it.text.isNotBlank() }) return null
        return Lyrics(plain, lines, instrumental)
    }

    private suspend fun get(url: HttpUrl): String? = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Lrclib-Client", "Spokify/0.1.0").header("User-Agent", "Spokify/0.1.0 (Android music player)").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (it.code == 404) return@use null
                        if (!it.isSuccessful) throw IOException("Lyrics provider returned ${it.code}")
                        val body = it.body
                        val source = body.source()
                        source.request(MAX_LYRICS_BYTES + 1L)
                        if (source.buffer.size > MAX_LYRICS_BYTES) throw IOException("Lyrics response is too large")
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}

private const val MAX_LYRICS_BYTES = 512 * 1024
