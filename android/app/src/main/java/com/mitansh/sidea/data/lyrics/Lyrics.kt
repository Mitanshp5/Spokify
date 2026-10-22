package com.mitansh.sidea.data.lyrics

import com.mitansh.sidea.core.Track
import java.text.Normalizer
import java.util.Locale

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val plainText: String, val lines: List<LyricLine>, val instrumental: Boolean = false) {
    val isSynced: Boolean get() = lines.isNotEmpty()

    /** Last timestamp at or before the player clock, including empty instrumental gaps. */
    fun activeIndex(positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        return high
    }
}

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(value: String?): List<LyricLine> {
        if (value.isNullOrBlank()) return emptyList()
        val adjustment = offset.find(value)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0L
        return value.lineSequence().flatMap { raw ->
            val stamps = timestamp.findAll(raw).toList()
            val text = timestamp.replace(raw, "").trim()
            stamps.asSequence().map { stamp ->
                val fraction = stamp.groupValues[3].padEnd(3, '0').take(3).toLong()
                val time = stamp.groupValues[1].toLong() * 60_000 + stamp.groupValues[2].toLong() * 1000 + fraction
                // LRC positive offsets advance the displayed lyrics.
                LyricLine((time - adjustment).coerceAtLeast(0), text)
            }
        }.distinct().sortedBy { it.timeMs }.take(5000).toList()
    }
}

internal data class LyricsQuery(val title: String, val artist: String, val durationMs: Long) {
    val key: String get() = "${normalize(title)}\n${normalize(artist)}\n${durationMs / 1000}"

    companion object {
        private val decoration = Regex("\\s*[\\[(](?:(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyrics?|visuali[sz]er)|(?:official\\s+)?lyric\\s+video)[\\])]", RegexOption.IGNORE_CASE)

        fun from(track: Track): LyricsQuery {
            val artist = track.artist.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").trim()
            var title = track.title.replace(decoration, "").trim()
            val prefix = "$artist - "
            if (artist.isNotBlank() && title.startsWith(prefix, ignoreCase = true)) title = title.drop(prefix.length).trim()
            return LyricsQuery(title, artist, track.durationMs.coerceAtLeast(0))
        }

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }
}
