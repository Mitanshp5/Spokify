package com.mitansh.sidea.data.imports

import android.content.Context
import com.mitansh.sidea.core.Track
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A durable import session.
 *
 * An import can take a while: a long playlist has to be read, matched and reviewed. The session
 * therefore survives a restart, so the user can come back to a half-finished import instead of
 * starting again. It is written as one JSON file per session in the app's private directory.
 */
class ImportSessionStore(private val folder: File) {

    constructor(context: Context) : this(File(context.filesDir, "import-sessions"))

    /** A session as it is stored. */
    data class Session(
        val id: String,
        val source: ImportedPlaylist.Source,
        val sourceId: String,
        val title: String,
        val author: String,
        val artworkUrl: String?,
        val declaredCount: Int?,
        val complete: Boolean,
        val items: List<ImportedItem>,
        /** The local playlist this session was committed to, so a repeat save does not duplicate it. */
        val committedPlaylistId: String? = null,
        val updatedAtEpochMs: Long = 0L,
        val warning: String? = null,
    )

    fun save(session: Session) {
        val root = JsonObject()
        root.put("id", session.id)
        root.put("source", session.source.name)
        root.put("sourceId", session.sourceId)
        root.put("title", session.title)
        root.put("author", session.author)
        session.artworkUrl?.let { root.put("artworkUrl", it) }
        session.declaredCount?.let { root.put("declaredCount", it) }
        root.put("complete", session.complete)
        session.committedPlaylistId?.let { root.put("committedPlaylistId", it) }
        root.put("updatedAtEpochMs", session.updatedAtEpochMs)
        session.warning?.let { root.put("warning", it) }
        val items = JsonArray()
        session.items.forEach { item ->
            val entry = JsonObject()
            entry.put("position", item.position)
            item.sourceTrackId?.let { entry.put("sourceTrackId", it) }
            entry.put("title", item.title)
            entry.put("artist", item.artist)
            entry.put("durationMs", item.durationMs)
            entry.put("status", item.status.name)
            item.sourceArtworkUrl?.let { entry.put("sourceArtworkUrl", it) }
            item.selected?.let { selected ->
                val track = JsonObject()
                track.put("id", selected.id)
                track.put("title", selected.title)
                track.put("artist", selected.artist)
                track.put("durationMs", selected.durationMs)
                selected.artworkUrl?.let { track.put("artworkUrl", it) }
                track.put("source", selected.source)
                entry.put("selected", track)
            }
            val alternatives = JsonArray()
            item.alternatives.forEach { alternative ->
                val track = JsonObject()
                track.put("id", alternative.id)
                track.put("title", alternative.title)
                track.put("artist", alternative.artist)
                track.put("durationMs", alternative.durationMs)
                alternative.artworkUrl?.let { track.put("artworkUrl", it) }
                track.put("source", alternative.source)
                alternatives.add(track)
            }
            entry.put("alternatives", alternatives)
            items.add(entry)
        }
        root.put("items", items)
        // The object's own toString is a debug format, not JSON, so the writer is used instead.
        val target = file(session.id)
        val staging = File(folder, target.name + ".tmp")
        try {
            FileOutputStream(staging).use { output ->
                output.write(com.grack.nanojson.JsonWriter.string(root).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            // Never delete a directory or the previously acknowledged target on failure.
            if (staging.isFile) staging.delete()
        }
        prune()
    }

    fun load(id: String): Session? {
        if (!validId(id)) return null
        val file = file(id)
        if (!file.isFile) return null
        return runCatching {
            val root = JsonParser.`object`().from(file.readText())
            require(root.getString("id") == id && root.getString("sourceId")?.isNotBlank() == true)
            require(root.getString("source") in ImportedPlaylist.Source.entries.map { it.name })
            val items = requireNotNull(root.getArray("items")).map { element ->
                val entry = requireNotNull(element as? JsonObject)
                val position = (entry["position"] as? Number)?.toLong()
                require(position != null && position in 0..Int.MAX_VALUE.toLong() &&
                    (entry["position"] as Number).toDouble() == position.toDouble())
                ImportedItem(
                    position = position.toInt(),
                    sourceTrackId = entry.getString("sourceTrackId"),
                    title = entry.getString("title").orEmpty(),
                    artist = entry.getString("artist").orEmpty(),
                    durationMs = entry.getLong("durationMs") ?: 0L,
                    sourceArtworkUrl = entry.getString("sourceArtworkUrl"),
                    selected = entry.getObject("selected")?.toTrack(),
                    alternatives = entry.getArray("alternatives")?.mapNotNull { alternative ->
                        (alternative as? JsonObject)?.toTrack()
                    } ?: emptyList(),
                    status = runCatching {
                        ImportedItem.Status.valueOf(entry.getString("status").orEmpty())
                    }.getOrDefault(ImportedItem.Status.MISSING),
                )
            }
            require(items.map { it.position }.distinct().size == items.size)
            Session(
                id = root.getString("id").orEmpty(),
                source = runCatching {
                    ImportedPlaylist.Source.valueOf(root.getString("source").orEmpty())
                }.getOrDefault(ImportedPlaylist.Source.SPOTIFY),
                sourceId = root.getString("sourceId").orEmpty(),
                title = root.getString("title").orEmpty(),
                author = root.getString("author").orEmpty(),
                artworkUrl = root.getString("artworkUrl"),
                declaredCount = if (root.containsKey("declaredCount")) root.getInt("declaredCount") else null,
                complete = root.getBoolean("complete") ?: false,
                items = items,
                committedPlaylistId = root.getString("committedPlaylistId"),
                updatedAtEpochMs = root.getLong("updatedAtEpochMs") ?: 0L,
                warning = root.getString("warning"),
            )
        }.getOrNull()
    }

    /** The sessions that have not been committed yet. */
    fun pending(): List<Session> = folder.listFiles()
        ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
        ?.mapNotNull { load(it.name.removeSuffix(SUFFIX)) }
        ?.filter { it.committedPlaylistId == null && it.items.isNotEmpty() &&
            it.items.all { row -> row.position >= 0 } && it.items.map { row -> row.position }.distinct().size == it.items.size }
        ?.sortedByDescending { it.updatedAtEpochMs }
        ?: emptyList()

    fun delete(id: String) {
        file(id).delete()
    }

    init {
        folder.mkdirs()
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    private fun file(id: String): File {
        require(validId(id)) { "Invalid import session identifier" }
        return File(folder, id + SUFFIX)
    }
    /** Keeps the number of stored sessions small, so the folder cannot grow without limit. */
    private fun prune() {
        val files = folder.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_SESSIONS).forEach { it.delete() }
    }

    private fun JsonObject.toTrack(): Track? {        val id = getString("id") ?: return null
        return Track(
            id = id,
            title = getString("title").orEmpty(),
            artist = getString("artist").orEmpty(),
            durationMs = getLong("durationMs") ?: 0L,
            artworkUrl = getString("artworkUrl"),
            source = getString("source") ?: "youtube",
        )
    }

    private fun JsonObject.toTrackOrNull(): Track? = toTrack()

    private companion object {
        const val SUFFIX = ".import.json"
        const val MAX_SESSIONS = 20
    }
}
