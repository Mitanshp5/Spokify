package com.mitansh.sidea.playback.feedback

import androidx.media3.common.Player

/** The reason the player moved to another item, reduced to the cases playback feedback cares about. */
enum class TrackChange { USER_CHANGE, AUTO, REPEAT, OTHER }

/**
 * Which way the queue moved.
 *
 * A forward jump is a deliberate skip past a song. A backward jump is a deliberate return to an
 * earlier song, and repeating the current song is not a move at all. Media3 reports all three as
 * the same seek transition, so the direction has to come from the queue index.
 *
 * With shuffle enabled the index no longer reflects the listening order, so a forward jump can
 * look backward. That only suppresses a skip record, which is the safe direction to fail in.
 */
enum class JumpDirection { FORWARD, BACKWARD, NONE }

/**
 * Decides which item changes deserve negative feedback.
 *
 * Only an explicit forward move (a Media3 seek that lands on a different item) that abandoned the
 * previous recording before it was really heard counts. Natural auto transitions, repeat, pause and
 * buffering are not user signals. This is the policy the playback listener calls; tests use the same
 * object rather than a parallel copy.
 */
object PlaybackFeedbackPolicy {

    /** Listening below this is treated as an abandoned recording. */
    const val EARLY_SKIP_MS = 15_000L

    fun changeFor(reason: Int): TrackChange = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> TrackChange.USER_CHANGE
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> TrackChange.AUTO
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> TrackChange.REPEAT
        else -> TrackChange.OTHER
    }

    /** The direction of a queue move, taken from the item index before and after it. */
    fun directionFor(previousIndex: Int, newIndex: Int): JumpDirection = when {
        newIndex > previousIndex -> JumpDirection.FORWARD
        newIndex < previousIndex -> JumpDirection.BACKWARD
        else -> JumpDirection.NONE
    }

    fun isNegativeFeedback(change: TrackChange, accumulatedMs: Long, direction: JumpDirection): Boolean =
        change == TrackChange.USER_CHANGE &&
            direction == JumpDirection.FORWARD &&
            accumulatedMs in 1 until EARLY_SKIP_MS
}
