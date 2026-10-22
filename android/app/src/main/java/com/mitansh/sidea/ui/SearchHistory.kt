package com.mitansh.sidea.ui

import android.content.Context
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.mitansh.sidea.core.Track
import java.util.Locale

data class RecentSearch(val key: String, val query: String? = null, val track: Track? = null)

interface SearchHistoryStorage {
    fun read(): String
    fun write(value: String)
}

class SearchHistory(private val storage: SearchHistoryStorage? = null) {
    private var entries = runCatching {
        JsonParser.array().from(storage?.read() ?: "[]").mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val query = row.getString("query")
            // nanojson getObject supplies an empty object for a missing key.
            val track = (row["track"] as? JsonObject)?.let {
                val id = it.getString("id")?.takeIf(String::isNotBlank) ?: return@let null
                Track(id, it.getString("title").orEmpty(), it.getString("artist").orEmpty(), it.getLong("durationMs"), it.getString("artworkUrl"), it.getString("source") ?: "youtube")
            }
            when {
                track != null && track.id.isNotBlank() -> RecentSearch("song:${track.uid}", track = track)
                !query.isNullOrBlank() -> RecentSearch(queryKey(query), query = query)
                else -> null
            }
        }.distinctBy { it.key }.take(LIMIT)
    }.getOrDefault(emptyList())

    fun list(): List<RecentSearch> = entries.toList()
    fun query(value: String) {
        val query = value.trim().replace(Regex("\\s+"), " ").take(200)
        if (query.isNotBlank()) add(RecentSearch(queryKey(query), query = query))
    }
    fun song(track: Track) = add(RecentSearch("song:${track.uid}", track = track))
    fun remove(key: String) { entries = entries.filterNot { it.key == key }; persist() }
    fun clear() { entries = emptyList(); persist() }
    private fun add(entry: RecentSearch) { entries = (listOf(entry) + entries.filterNot { it.key == entry.key }).take(LIMIT); persist() }
    private fun persist() {
        val array = JsonArray()
        entries.forEach { entry ->
            val row = JsonObject()
            entry.query?.let { row["query"] = it }
            entry.track?.let {
                row["track"] = JsonObject().apply {
                    put("id", it.id); put("title", it.title); put("artist", it.artist)
                    put("durationMs", it.durationMs); put("artworkUrl", it.artworkUrl); put("source", it.source)
                }
            }
            array.add(row)
        }
        storage?.write(JsonWriter.string(array))
    }
    companion object {
        const val LIMIT = 20
        private fun queryKey(value: String) = "query:" + value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        fun persistent(context: Context): SearchHistory {
            val prefs = context.getSharedPreferences("search-history", Context.MODE_PRIVATE)
            return SearchHistory(object : SearchHistoryStorage {
                override fun read() = prefs.getString("entries", "[]") ?: "[]"
                override fun write(value: String) { prefs.edit().putString("entries", value).apply() }
            })
        }
    }
}
