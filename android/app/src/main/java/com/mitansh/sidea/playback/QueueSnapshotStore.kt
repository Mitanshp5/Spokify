package com.mitansh.sidea.playback

import android.content.Context
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.mitansh.sidea.core.Track
import java.io.File

/**
 * The queue and the position, kept so a session survives a restart.
 *
 * Without this the app comes back to an empty queue after a restart and the user has to start
 * again. The snapshot is small and written only when the queue really changes, so it does not
 * become a source of lag.
 */
class QueueSnapshotStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "queue-snapshot.json"))

    /** What was playing, and where in the queue. */
    data class Snapshot(
        val items: List<Track>,
        val currentIndex: Int,
        val positionMs: Long,
        val seed: Track?,
        val occurrences: List<QueueOccurrence> = items.map { QueueOccurrence() },
        val shuffled: Boolean = false,
        val canonicalOrder: List<String> = occurrences.map { it.id },
    )

    fun save(snapshot: Snapshot) {
        val root = JsonObject()
        root.put("currentIndex", snapshot.currentIndex)
        root.put("positionMs", snapshot.positionMs)
        root.put("shuffled", snapshot.shuffled)
        root.put("canonicalOrder", JsonArray().apply { addAll(snapshot.canonicalOrder) })
        val items = JsonArray()
        snapshot.items.forEachIndexed { index, track ->
            val entry = JsonObject()
            entry.put("id", track.id)
            entry.put("title", track.title)
            entry.put("artist", track.artist)
            entry.put("durationMs", track.durationMs)
            track.artworkUrl?.let { entry.put("artworkUrl", it) }
            entry.put("source", track.source)
            snapshot.occurrences.getOrNull(index)?.let {
                entry.put("occurrence", it.id)
                entry.put("origin", it.origin.name)
                it.context?.let { title -> entry.put("context", title) }
            }
            items.add(entry)
        }
        root.put("items", items)
        snapshot.seed?.let { seed ->
            val entry = JsonObject()
            entry.put("id", seed.id)
            entry.put("title", seed.title)
            entry.put("artist", seed.artist)
            entry.put("durationMs", seed.durationMs)
            seed.artworkUrl?.let { entry.put("artworkUrl", it) }
            entry.put("source", seed.source)
            root.put("seed", entry)
        }
        runCatching { file.writeText(JsonWriter.string(root)) }
    }

    fun load(): Snapshot? {
        if (!file.isFile) return null
        return runCatching {
            val root = JsonParser.`object`().from(file.readText())
            val items = root.getArray("items")?.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val id = entry.getString("id") ?: return@mapNotNull null
                Track(
                    id = id,
                    title = entry.getString("title").orEmpty(),
                    artist = entry.getString("artist").orEmpty(),
                    durationMs = entry.getLong("durationMs") ?: 0L,
                    artworkUrl = entry.getString("artworkUrl"),
                    source = entry.getString("source") ?: "youtube",
                )
            } ?: emptyList()
            if (items.isEmpty()) return null
            val restored = Snapshot(
                items = items,
                currentIndex = (root.getInt("currentIndex") ?: 0).coerceIn(0, items.size - 1),
                positionMs = root.getLong("positionMs") ?: 0L,
                seed = root.getObject("seed")?.toTrack(),
                occurrences = root.getArray("items")?.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val id = entry.getString("occurrence") ?: return@mapNotNull null
                    QueueOccurrence(id, runCatching { QueueOrigin.valueOf(entry.getString("origin").orEmpty()) }.getOrDefault(QueueOrigin.CONTEXT), entry.getString("context"))
                }?.takeIf { it.size == items.size && it.map { e -> e.id }.distinct().size == items.size } ?: items.map { QueueOccurrence() },
                shuffled = root.getBoolean("shuffled", false),
            )
            val savedOrder = root.getArray("canonicalOrder")?.mapNotNull { it as? String }
            restored.copy(canonicalOrder = savedOrder?.takeIf {
                it.size == restored.occurrences.size && it.toSet() == restored.occurrences.map { e -> e.id }.toSet()
            } ?: restored.occurrences.map { it.id })
        }.getOrNull()
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun JsonObject.toTrack(): Track? {
        val id = getString("id") ?: return null
        return Track(
            id = id,
            title = getString("title").orEmpty(),
            artist = getString("artist").orEmpty(),
            durationMs = getLong("durationMs") ?: 0L,
            artworkUrl = getString("artworkUrl"),
            source = getString("source") ?: "youtube",
        )
    }
}
