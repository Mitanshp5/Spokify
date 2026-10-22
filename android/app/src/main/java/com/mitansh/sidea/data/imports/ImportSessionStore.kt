package com.mitansh.sidea.data.imports

import android.content.Context
import com.mitansh.sidea.core.Track
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.File

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
        file(session.id).writeText(com.grack.nanojson.JsonWriter.string(root))
        prune()
    }

    fun load(id: String): Session? {
        val file = file(id)
        if (!file.isFile) return null
        return runCatching {
            val root = JsonParser.`object`().from(file.readText())
            val items = root.getArray("items")?.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                ImportedItem(
                    position = entry.getInt("position") ?: 0,
                    sourceTrackId = entry.getString("sourceTrackId"),
                    title = entry.getString("title").orEmpty(),
                    artist = entry.getString("artist").orEmpty(),
                    durationMs = entry.getLong("durationMs") ?: 0L,
                    selected = entry.getObject("selected")?.toTrack(),
                    alternatives = entry.getArray("alternatives")?.mapNotNull { alternative ->
                        (alternative as? JsonObject)?.toTrack()
                    } ?: emptyList(),
                    status = runCatching {
                        ImportedItem.Status.valueOf(entry.getString("status").orEmpty())
                    }.getOrDefault(ImportedItem.Status.MISSING),
                )
            } ?: emptyList()
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
        ?.filter { it.committedPlaylistId == null }
        ?.sortedByDescending { it.updatedAtEpochMs }
        ?: emptyList()

    fun delete(id: String) {
        file(id).delete()
    }

    init {
        folder.mkdirs()
    }

    private fun file(id: String) = File(folder, id + SUFFIX)
    /** Keeps the number of stored sessions small, so the folder cannot grow without limit. */
    private fun prune() {
        val files = folder.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
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
