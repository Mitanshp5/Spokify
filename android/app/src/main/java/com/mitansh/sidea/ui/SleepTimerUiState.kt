package com.mitansh.sidea.ui

internal val sleepTimerPresets = listOf(5, 10, 15, 30, 45, 60)
internal fun sleepTimerCustomMinutes(input: String): Int? =
    input.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 1..180 }

/** Service snapshot, never an Activity-owned playback timer. */
data class SleepTimerStatus(val mode: String, val remainingMs: Long) {
    val active: Boolean get() = mode != "off"
    val summary: String get() = when (mode) {
        "duration" -> {
            val seconds = (remainingMs / 1000L + if (remainingMs % 1000L > 0L) 1L else 0L).coerceAtLeast(1L)
            "Stops in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
        "end_of_track" -> "Stops after this song"
        else -> "Sleep timer off"
    }
}

internal fun sleepTimerStatus(mode: Any?, deadlineMs: Any?, remainingMs: Any?, endOfTrack: Any?): SleepTimerStatus? {
    if (mode !is String || deadlineMs !is Long || remainingMs !is Long || endOfTrack !is Boolean) return null
    val valid = when (mode) {
        "duration" -> deadlineMs > 0L && remainingMs >= 0L && !endOfTrack
        "off" -> deadlineMs == 0L && remainingMs == 0L && !endOfTrack
        "end_of_track" -> deadlineMs == 0L && remainingMs == 0L && endOfTrack
        else -> false
    }
    return if (valid) SleepTimerStatus(mode, remainingMs) else null
}

data class SleepTimerUiState(
    val status: SleepTimerStatus? = null,
    val pending: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
) {
    val summary: String get() = status?.summary ?: "Checking sleep timer…"
}

/** Serializes presentation updates, not playback. Tokens fence late GET/controller callbacks. */
internal class SleepTimerRequests {
    var state = SleepTimerUiState()
        private set
    private var generation = 0L
    private var inFlight = false
    private var mounted = true

    fun begin(setting: Boolean): Long? {
        if (!mounted || state.pending || (!setting && inFlight)) return null
        inFlight = true
        state = state.copy(pending = setting, error = if (setting) null else state.error)
        return ++generation
    }

    fun complete(token: Long, success: Boolean, status: SleepTimerStatus?, error: String? = null): Boolean {
        if (!mounted || token != generation || !inFlight) return false
        inFlight = false
        val wasSetting = state.pending
        state = state.copy(
            status = status ?: state.status,
            ready = status != null,
            pending = false,
            error = when {
                !success -> error ?: "Couldn't update sleep timer. Try again."
                status == null -> "Sleep timer status unavailable. Try again."
                wasSetting -> null
                else -> state.error
            },
        )
        return true
    }

    fun dispose() { mounted = false; generation++ }
}
