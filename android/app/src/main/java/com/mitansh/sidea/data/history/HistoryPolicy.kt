package com.mitansh.sidea.data.history

/**
 * Decides when real playback deserves a durable history entry.
 *
 * History is written from actual playback, never from a tap: a track must have played for 30
 * seconds (or half its length when shorter) before it is remembered, so abandoned taps and failed
 * resolutions never pollute the local library.
 */
object HistoryPolicy {

    /** Continuous playback required before a normal-length track is remembered. */
    const val MIN_LISTEN_MS = 30_000L

    /** A completion still needs this much real playback to count. */
    const val MIN_ANY_LISTEN_MS = 5_000L

    /** Tracks shorter than this are judged against half their own length. */
    private const val SHORT_TRACK_MS = 60_000L

    /** Upper bound on how many distinct tracks local history keeps. */
    const val HISTORY_LIMIT = 200

    fun shouldRecord(accumulatedMs: Long, durationMs: Long, completed: Boolean): Boolean {
        if (accumulatedMs < MIN_ANY_LISTEN_MS) return false
        if (completed) return true

        val threshold = if (durationMs in 1..SHORT_TRACK_MS) durationMs / 2 else MIN_LISTEN_MS
        return accumulatedMs >= threshold
    }

    /** Returns the ids to delete from a history list ordered most-recent-first. */
    fun idsToEvict(mostRecentFirst: List<String>, limit: Int): List<String> =
        if (limit <= 0) mostRecentFirst.toList() else mostRecentFirst.drop(limit)
}
