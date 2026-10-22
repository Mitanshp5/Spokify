package com.mitansh.sidea.data.offline

import com.mitansh.sidea.core.Track
import java.util.Base64

/**
 * The songs that qualified for automatic saving but are not saved yet.
 *
 * A song can qualify on mobile data while the automatic downloads are limited to unmetered
 * networks. Remembering the candidate means the song is still saved later, when an allowed network
 * becomes available, without the user having to play it again.
 *
 * The store is kept free of Android APIs so it can be unit tested, and it is a plain list in
 * shared preferences: the number of candidates is small and bounded by the play threshold.
 */
class PendingAutomaticStore(
    private val read: () -> String,
    private val write: (String) -> Unit,
) {

    data class Candidate(val track: Track, val playCount: Int, val lastPlayedAtEpochMs: Long)

    /** The candidates that qualified, most played first. */
    fun candidates(): List<Candidate> = entries()
        .mapNotNull { (id, candidate) -> id to candidate }
        .sortedByDescending { it.second.playCount }
        .map { it.second }

    fun isEmpty(): Boolean = entries().isEmpty()

    fun size(): Int = entries().size

    /**
     * Remembers a qualified song. A song heard again replaces its earlier entry.
     *
     * The last play time travels with the entry, so a song that was played often but long ago can
     * be dropped when the recent window moves past it.
     */
    fun remember(track: Track, playCount: Int, lastPlayedAtEpochMs: Long) {
        if (!AutomaticDownloadPolicy.isQualified(playCount)) return
        val encoded = Base64.getEncoder().encodeToString(OfflineMetadata.of(track, automatic = true).encode())
        val line = "${track.id}|$playCount|$lastPlayedAtEpochMs|$encoded"
        val kept = lines().filter { it.isNotBlank() && !it.startsWith("${track.id}|") }
        write((kept + line).joinToString("\n"))
    }

    /** Forgets a song, because it is now saved or the user removed it. */
    fun forget(id: String) {
        write(lines().filter { it.isNotBlank() && !it.startsWith("$id|") }.joinToString("\n"))
    }

    fun clear() = write("")

    private fun lines(): List<String> = read().split('\n')

    private fun entries(): List<Pair<String, Candidate>> = lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val separator = line.indexOf('|')
        if (separator <= 0) return@mapNotNull null
        val id = line.substring(0, separator)
        val rest = line.substring(separator + 1)
        val playCount = rest.substringBefore('|').toIntOrNull() ?: return@mapNotNull null
        val lastPlayedAt = rest.substringAfter('|', "").substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        val encoded = rest.substringAfter('|', "").substringAfter('|', "")
        if (encoded.isBlank()) return@mapNotNull null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return@mapNotNull null
        val metadata = OfflineMetadata.decode(bytes) ?: return@mapNotNull null
        id to Candidate(metadata.toTrack(id), playCount, lastPlayedAt)
    }
}
