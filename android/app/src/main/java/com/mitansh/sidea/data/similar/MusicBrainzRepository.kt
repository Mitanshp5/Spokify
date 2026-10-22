package com.mitansh.sidea.data.similar

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves artist names to MusicBrainz ids and caches the answer.
 *
 * MusicBrainz rate-limits bursts, so a lookup that is repeated for every radio request would
 * either be slow or fail. The cache is keyed by the normalized artist name, and entries never
 * expire during a session, so a seed is only looked up once.
 */
open class MusicBrainzRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {

    private val artistCache = ConcurrentHashMap<String, String>()

    /**
     * The MusicBrainz id for an artist name, or null when the name is not known.
     *
     * The first exact-looking match is used; MusicBrainz orders its results by search score, so
     * the disambiguation field of the caller should still be checked when it matters.
     */
    open fun artistId(artistName: String): String? {
        val key = normalize(artistName)
        if (key.isEmpty()) return null
        artistCache[key]?.let { return it }
        val found = searchArtist(artistName) ?: return null
        artistCache[key] = found
        return found
    }

    /** The recording id for an artist and title, or null when there is no confident match. */
    open fun recordingId(artistName: String, title: String): String? {
        val artist = normalize(artistName)
        val song = normalize(title)
        if (artist.isEmpty() || song.isEmpty()) return null
        val cacheKey = "$artist|$song"
        recordingCache[cacheKey]?.let { return it }
        val found = searchRecording(artistName, title) ?: return null
        recordingCache[cacheKey] = found
        return found
    }

    /** The artist and title of a recording id, for a seed the app already knows. */
    fun recordingArtistTitle(recordingMbid: String): Pair<String, String>? {
        val body = get("$MUSICBRAINZ/ws/2/recording/$recordingMbid?inc=artist-credits&fmt=json") ?: return null
        val title = body.getString("title") ?: return null
        val credits = body.getArray("artist-credit") ?: return null
        val artist = credits.joinToString(", ") { (it as? JsonObject)?.getString("name").orEmpty() }
        return artist to title
    }

    private fun searchArtist(artistName: String): String? {
        val url = "$MUSICBRAINZ/ws/2/artist/?query=${encode("artist:\"$artistName\"")}&fmt=json&limit=1"
        val body = get(url) ?: return null
        val artists = body.getArray("artists") ?: return null
        return (artists.firstOrNull() as? JsonObject)?.getString("id")
    }

    private fun searchRecording(artistName: String, title: String): String? {
        val url = "$MUSICBRAINZ/ws/2/recording/?query=${encode("recording:\"$title\" AND artist:\"$artistName\"")}&fmt=json&limit=1"
        val body = get(url) ?: return null
        val recordings = body.getArray("recordings") ?: return null
        return (recordings.firstOrNull() as? JsonObject)?.getString("id")
    }

    private fun get(url: String): JsonObject? = try {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) return null
            JsonParser.`object`().from(source.readUtf8())
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val recordingCache = ConcurrentHashMap<String, String>()

    companion object {
        private const val MUSICBRAINZ = "https://musicbrainz.org"

        private const val USER_AGENT = "Spokify/0.1 (personal music player)"

        private const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
    }
}
