package com.mitansh.sidea.data.similar

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reads artist and track neighbourhoods from the public MusicBrainz and ListenBrainz services.
 *
 * Spotify's own radio is a server-side model that cannot be copied from the shipped app. These
 * services expose the same *kind* of signal, a collaborative-filtering neighbourhood built from a
 * large public listen stream, and they need no account, key or token.
 *
 * Every call is a plain HTTPS GET with a bounded response, and every result carries the score the
 * service assigned, so the caller can order by relevance instead of guessing.
 */
open class SimilarityRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {

    /** An artist that sounds like the seed, with the service's own relevance score. */
    data class SimilarArtist(
        val mbid: String,
        val name: String,
        val score: Int,
    )

    /** A recording that sounds like the seed, with the service's own relevance score. */
    data class SimilarRecording(
        val mbid: String,
        val title: String,
        val artist: String,
        val release: String,
        val score: Int,
    )

    /**
     * Finds the artists that listeners pair with [artistName].
     *
     * The result is empty when the service has no neighbourhood for the artist, which happens for
     * very obscure names; the caller falls back to its own feed in that case.
     */
    open fun similarArtists(artistMbid: String, limit: Int = 12): List<SimilarArtist> {
        if (artistMbid.isBlank()) return emptyList()
        val url = "$LABS/similar-artists/json?artist_mbids=$artistMbid&algorithm=$ARTIST_ALGORITHM"
        val rows = getArray(url) ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val mbid = row.getString("artist_mbid") ?: return@mapNotNull null
            val name = row.getString("name") ?: return@mapNotNull null
            SimilarArtist(mbid, name, row.getInt("score") ?: 0)
        }.take(limit.coerceIn(0, MAX_RESULTS))
    }

    /**
     * Finds the recordings that listeners pair with [recordingMbid].
     *
     * The service returns an empty list for recordings it has too little data about, so an empty
     * result is a normal answer rather than a failure.
     */
    open fun similarRecordings(recordingMbid: String, limit: Int = 25): List<SimilarRecording> {
        if (recordingMbid.isBlank()) return emptyList()
        val url = "$LABS/similar-recordings/json?recording_mbids=$recordingMbid&algorithm=$RECORDING_ALGORITHM"
        val rows = getArray(url) ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val mbid = row.getString("recording_mbid") ?: return@mapNotNull null
            val title = row.getString("recording_name") ?: return@mapNotNull null
            SimilarRecording(
                mbid = mbid,
                title = title,
                artist = row.getString("artist_credit_name").orEmpty(),
                release = row.getString("release_name").orEmpty(),
                score = row.getInt("score") ?: 0,
            )
        }.take(limit.coerceIn(0, MAX_RESULTS))
    }

    /**
     * Finds the recording that matches an artist and title.
     *
     * The service matches loosely, so the results include covers and re-recordings. The artist
     * credit is returned with each row so the caller can reject a row that is not the original.
     */
    fun searchRecordings(artistName: String, title: String, limit: Int = 5): List<SimilarRecording> {
        val query = "$artistName $title".trim()
        if (query.isBlank()) return emptyList()
        val url = "$LABS/recording-search/json?query=${encode(query)}"
        val rows = getArray(url) ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val mbid = row.getString("recording_mbid") ?: return@mapNotNull null
            val name = row.getString("recording_name") ?: return@mapNotNull null
            SimilarRecording(
                mbid = mbid,
                title = name,
                artist = row.getString("artist_credit_name").orEmpty(),
                release = row.getString("release_name").orEmpty(),
                score = 0,
            )
        }.take(limit.coerceIn(0, MAX_RESULTS))
    }

    /** The canonical recording details, used to check a match before trusting it. */
    fun recordingDetails(recordingMbid: String): SimilarRecording? {
        if (recordingMbid.isBlank()) return null
        val url = "$LABS/recording-mbid-lookup/json?recording_mbid=$recordingMbid"
        val rows = getArray(url) ?: return null
        val row = rows.firstOrNull() as? JsonObject ?: return null
        val mbid = row.getString("recording_mbid") ?: return null
        return SimilarRecording(
            mbid = mbid,
            title = row.getString("recording_name").orEmpty(),
            artist = row.getString("artist_credit_name").orEmpty(),
            release = row.getString("release_name").orEmpty(),
            score = 0,
        )
    }

    private fun getArray(url: String): JsonArray? = try {
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
            val parsed = JsonParser.any().from(source.readUtf8())
            parsed as? JsonArray
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val LABS = "https://labs.api.listenbrainz.org"

        /** The wide-window artist algorithm; the service validates this value. */
        const val ARTIST_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"

        /** The wide-window recording algorithm, with the listener-weighted variant. */
        const val RECORDING_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_15_limit_50_skip_30_top_n_listeners_1000"

        private const val USER_AGENT = "Spokify/0.1 (personal music player)"

        private const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L

        private const val MAX_RESULTS = 100
    }
}
