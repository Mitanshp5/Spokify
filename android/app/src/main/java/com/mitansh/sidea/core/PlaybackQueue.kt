package com.mitansh.sidea.core

/**
 * The ordered list of tracks currently playing, built from the search results the user tapped.
 *
 * Navigation clamps instead of throwing so a stale index (for example restored from the last
 * session) can be handed straight to the queue.
 */
class PlaybackQueue(items: List<Track>, startIndex: Int = 0) {

    private val tracks: List<Track> = items.toList()

    var currentIndex: Int = if (tracks.isEmpty()) 0 else startIndex.coerceIn(0, tracks.size - 1)
        private set

    val size: Int get() = tracks.size

    val current: Track? get() = tracks.getOrNull(currentIndex)

    fun snapshot(): List<Track> = tracks

    fun hasNext(): Boolean = currentIndex + 1 < tracks.size

    fun hasPrevious(): Boolean = currentIndex > 0

    fun moveNext(): Track? {
        if (hasNext()) currentIndex++
        return current
    }

    fun movePrevious(): Track? {
        if (hasPrevious()) currentIndex--
        return current
    }

    fun jumpTo(target: Int): Track? {
        if (tracks.isNotEmpty()) currentIndex = target.coerceIn(0, tracks.size - 1)
        return current
    }
}
