package com.mitansh.sidea.data.history

/**
 * Accumulates how much of the current track has actually played.
 *
 * Only forwards progress between playing samples counts; paused gaps, seeks and stalls are
 * rebaselined instead of being added, so [accumulatedMs] is a lower bound on real listening time.
 */
class ListeningSession {

    var accumulatedMs: Long = 0L
        private set

    private var lastPositionMs: Long = NO_POSITION

    fun startTrack(positionMs: Long) {
        accumulatedMs = 0L
        lastPositionMs = positionMs
    }

    fun onPlayingPosition(positionMs: Long) {
        val last = lastPositionMs
        if (last != NO_POSITION) {
            val delta = positionMs - last
            if (delta in 1..MAX_SAMPLE_GAP_MS) accumulatedMs += delta
        }
        lastPositionMs = positionMs
    }

    fun onPaused() {
        lastPositionMs = NO_POSITION
    }

    fun onSeek(positionMs: Long) {
        lastPositionMs = positionMs
    }

    private companion object {
        const val NO_POSITION = -1L

        /** A longer gap is a stall or a jump, not listening. */
        const val MAX_SAMPLE_GAP_MS = 5_000L
    }
}
