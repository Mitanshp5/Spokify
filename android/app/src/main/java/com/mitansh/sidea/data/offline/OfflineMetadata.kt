package com.mitansh.sidea.data.offline

import com.mitansh.sidea.core.Track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Track metadata persisted in a [androidx.media3.exoplayer.offline.DownloadRequest]'s `data` field.
 *
 * The request itself only keeps the stable `sidea-track` URI; everything the UI needs to render an
 * offline row (and to build a playback [Track]) travels here so no signed media URL is ever stored.
 * The encoding is a plain length-prefixed stream so it round-trips on the JVM without Android APIs.
 */
data class OfflineMetadata(
    val source: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val automatic: Boolean,
) {
    fun toTrack(id: String): Track = Track(id, title, artist, durationMs, artworkUrl, source)

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(source)
            out.writeUTF(title)
            out.writeUTF(artist)
            out.writeLong(durationMs)
            out.writeBoolean(artworkUrl != null)
            artworkUrl?.let(out::writeUTF)
            out.writeBoolean(automatic)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val VERSION = 1

        fun of(track: Track, automatic: Boolean): OfflineMetadata =
            OfflineMetadata(track.source, track.title, track.artist, track.durationMs, track.artworkUrl, automatic)

        /** Returns null for absent or unreadable payloads instead of inventing a partial record. */
        fun decode(bytes: ByteArray?): OfflineMetadata? {
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    if (input.readInt() != VERSION) return null
                    val source = input.readUTF()
                    val title = input.readUTF()
                    val artist = input.readUTF()
                    val durationMs = input.readLong()
                    val artworkUrl = if (input.readBoolean()) input.readUTF() else null
                    val automatic = input.readBoolean()
                    OfflineMetadata(source, title, artist, durationMs, artworkUrl, automatic)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
