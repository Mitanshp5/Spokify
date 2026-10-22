package com.mitansh.sidea.playback.feedback

import android.content.Context
import com.mitansh.sidea.data.youtube.SongRadioMixer

/** Durable blob backing [ArtistFeedbackStore]; a small map is all that is kept. */
interface FeedbackStorage {
    fun load(): Map<String, Int>
    fun save(weights: Map<String, Int>)
}

/**
 * Bounded artist-level negative feedback from abandoned recordings.
 *
 * The library already excludes previously seen ids from later radio batches, so an id-only weight
 * would never be consulted again; weights are therefore keyed by normalized artist and only ever
 * nudge ranking (never ban). Qualified listening of the same artist softens earlier skips.
 */
class ArtistFeedbackStore(private val storage: FeedbackStorage) {

    fun weights(): Map<String, Int> = storage.load()

    fun recordSkip(artist: String) = adjust(artist, +1)

    fun recordQualified(artist: String) = adjust(artist, -1)

    private fun adjust(artist: String, delta: Int) {
        val key = SongRadioMixer.normalizeArtist(artist)
        if (key.isEmpty()) return
        val weights = storage.load().toMutableMap()
        val next = ((weights[key] ?: 0) + delta).coerceIn(0, MAX_WEIGHT)
        if (next == 0) weights.remove(key) else weights[key] = next
        while (weights.size > MAX_ARTISTS) {
            weights.remove(weights.minByOrNull { it.value }!!.key)
        }
        storage.save(weights)
    }

    private companion object {
        const val MAX_WEIGHT = 10
        const val MAX_ARTISTS = 50
    }
}

/** Persists the bounded weight map in one SharedPreferences string; keys are alphanumeric. */
class SharedPreferencesFeedbackStorage(
    context: Context,
    name: String = "playback_feedback",
) : FeedbackStorage {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load(): Map<String, Int> = prefs.getString(KEY, "").orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val split = line.lastIndexOf('=')
            if (split <= 0) return@mapNotNull null
            line.substring(split + 1).toIntOrNull()?.let { value -> line.substring(0, split) to value }
        }
        .toMap()

    override fun save(weights: Map<String, Int>) {
        prefs.edit().putString(KEY, weights.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    private companion object { const val KEY = "artist_weights" }
}
