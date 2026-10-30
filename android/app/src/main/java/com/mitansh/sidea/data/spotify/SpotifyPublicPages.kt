package com.mitansh.sidea.data.spotify

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.math.BigDecimal
import java.net.URLEncoder

/** Public web-player metadata only. Session authorization stays in memory for this read. */
internal object SpotifyPublicPages {
    const val MAX_TRACKS = 2000
    private val script = Regex("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    fun anonymousToken(html: String): String? {
        val json = script.find(html)?.groupValues?.get(1) ?: return null
        val root = JsonParser.any().from(json) as? Map<*, *> ?: return null
        return root.map("props").map("pageProps").map("state").map("settings").map("session")["accessToken"]
            ?.let { it as? String }?.takeIf { it.isNotBlank() && it.length <= 16384 && '\n' !in it && '\r' !in it }
    }

    fun url(id: String, offset: Int): String {
        require(Regex("[A-Za-z0-9]{22}").matches(id) && offset in 0 until MAX_TRACKS)
        val variables = JsonWriter.string(mapOf("uri" to "spotify:playlist:$id", "offset" to offset, "limit" to 100, "includeItemV3" to false))
        // Persisted query shipped by Spotify's public mobile web player. If it changes, fall back
        // to an explicitly labelled preview instead of claiming the first 100 rows are complete.
        val extensions = JsonWriter.string(mapOf("persistedQuery" to mapOf("version" to 1, "sha256Hash" to "c685ca91fb0019c87ff1225f84e1d85b26cd631fa6187f3f0900da182cb0953d")))
        return "https://api-partner.spotify.com/pathfinder/v1/query?operationName=queryPlaylist&variables=${URLEncoder.encode(variables, "UTF-8")}&extensions=${URLEncoder.encode(extensions, "UTF-8")}"
    }

    data class Page(val title: String, val tracks: List<SpotifySourceTrack>, val total: Int, val next: Int?)

    fun parse(body: String, id: String, offset: Int): Page {
        val root = JsonParser.any().from(body) as? Map<*, *> ?: error("Invalid Spotify playlist response")
        require((root["errors"] as? List<*>)?.isNotEmpty() != true) { "Spotify public playlist query unavailable" }
        val playlist = root.map("data").map("playlistV2")
        require(playlist["uri"] == "spotify:playlist:$id") { "Spotify returned a different playlist" }
        val content = playlist.map("content")
        val total = nonnegativeInt(content["totalCount"])
        val items = content["items"] as? List<*> ?: error("Spotify playlist rows missing")
        require(items.size <= 100 && offset + items.size <= total) { "Invalid Spotify playlist page" }
        val tracks = items.mapIndexed { index, value ->
            val row = value as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val data = row.map("itemV2").map("data")
            val artist = (data.map("artists")["items"] as? List<*>).orEmpty().mapNotNull {
                (it as? Map<*, *>)?.map("profile")?.get("name") as? String
            }.joinToString(", ")
            val uri = data["uri"] as? String
            SpotifySourceTrack(
                position = offset + index,
                title = data["name"] as? String ?: "",
                artist = artist,
                durationMs = (data.map("duration")["totalMilliseconds"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                sourceId = uri?.takeIf { it.startsWith("spotify:track:") }?.removePrefix("spotify:track:")?.takeIf { Regex("[A-Za-z0-9]{22}").matches(it) },
                savedAt = row.map("addedAt")["isoString"] as? String ?: "",
            )
        }
        val paging = content.map("pagingInfo")
        require(paging.containsKey("nextOffset")) { "Spotify playlist cursor missing" }
        val rawNext = paging["nextOffset"]
        val next = rawNext?.let(::nonnegativeInt)
        // Validate the cursor in the reader, after retaining this page's valid rows.
        return Page(playlist["name"] as? String ?: "", tracks, total, next)
    }

    private fun nonnegativeInt(value: Any?): Int {
        require(value is Number) { "Invalid Spotify playlist count or cursor" }
        return BigDecimal(value.toString()).intValueExact().also {
            require(it >= 0) { "Invalid Spotify playlist count or cursor" }
        }
    }

    private fun Map<*, *>.map(key: String): Map<*, *> = get(key) as? Map<*, *> ?: emptyMap<Any?, Any?>()
}
