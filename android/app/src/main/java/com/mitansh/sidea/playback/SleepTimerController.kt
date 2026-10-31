package com.mitansh.sidea.playback

/** Service-owned policy. All calls are serialized on the player's application thread. */
class SleepTimerController(
    private val elapsedRealtime: () -> Long,
    private val pause: () -> Unit,
    private val onTransition: (State) -> Unit,
    private val getPauseAtEnd: () -> Boolean = { false },
    private val setPauseAtEnd: (Boolean) -> Unit = {},
) {
    data class State(val mode: String = "off", val deadlineElapsedRealtimeMs: Long = 0L)

    var state = State()
        private set
    private var closed = false
    private var previousPauseAtEnd = false

    fun command(mode: String, durationMs: Long = 0L, hasCurrentTrack: Boolean = true): Boolean {
        if (closed) return false
        if (mode == "off") {
            transition(State())
            return true
        }
        if (mode == "end_of_track") {
            if (!hasCurrentTrack) return false
            transition(State(mode))
            return true
        }
        if (mode != "duration" || durationMs !in 1_000L..86_400_000L) return false
        transition(State(mode, elapsedRealtime() + durationMs))
        return true
    }

    fun tick() {
        if (state.mode == "duration" && elapsedRealtime() >= state.deadlineElapsedRealtimeMs) {
            complete()
        }
    }

    fun remainingMs(): Long = if (state.mode == "duration")
        (state.deadlineElapsedRealtimeMs - elapsedRealtime()).coerceAtLeast(0L) else 0L

    fun close() {
        closed = true
        transition(State())
    }

    fun onTrackEnded() {
        if (state.mode == "end_of_track") complete()
    }

    /** Automatic includes repeat-one; a user skip or replacement cancels, never follows it. */
    fun onTrackTransition(automatic: Boolean) {
        if (state.mode != "end_of_track") return
        if (automatic) complete() else transition(State())
    }

    private fun complete() {
        val previous = state
        // Clear before pause: player callbacks may synchronously re-enter this controller.
        state = State()
        pause()
        notifyTransition(previous)
    }

    private fun transition(next: State) {
        if (state == next) return
        val previous = state
        state = next
        notifyTransition(previous)
    }

    private fun notifyTransition(previous: State) {
        if (previous.mode != "end_of_track" && state.mode == "end_of_track") {
            previousPauseAtEnd = getPauseAtEnd()
            setPauseAtEnd(true)
        } else if (previous.mode == "end_of_track" && state.mode != "end_of_track") {
            setPauseAtEnd(previousPauseAtEnd)
        }
        onTransition(state)
    }
}
