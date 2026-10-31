package com.mitansh.sidea.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** MediaController contract. All times are milliseconds; deadlines use elapsedRealtime, not UTC. */
object SleepTimerCommands {
    const val ACTION_SET = "com.mitansh.sidea.SLEEP_TIMER_SET"
    const val ACTION_GET = "com.mitansh.sidea.SLEEP_TIMER_GET"
    const val KEY_MODE = "sleepTimer.mode"
    const val KEY_DURATION_MS = "sleepTimer.durationMs"
    const val KEY_DEADLINE_MS = "sleepTimer.deadlineElapsedRealtimeMs"
    const val KEY_REMAINING_MS = "sleepTimer.remainingMs"
    const val KEY_END_OF_TRACK = "sleepTimer.endOfTrack"
    const val MODE_OFF = "off"
    const val MODE_DURATION = "duration"
    const val MODE_END_OF_TRACK = "end_of_track"
    val SET: SessionCommand get() = SessionCommand(ACTION_SET, Bundle.EMPTY)
    val GET: SessionCommand get() = SessionCommand(ACTION_GET, Bundle.EMPTY)
}

/** The same typed/trust validation seam used by the service's actual custom-command callback. */
class SleepTimerCommandDispatcher(private val timer: SleepTimerController) {
    enum class Result { SUCCESS, BAD_VALUE, PERMISSION_DENIED, INVALID_STATE, NOT_SUPPORTED }

    fun dispatch(
        action: String,
        trusted: Boolean,
        mode: Any? = null,
        durationMs: Any? = null,
        hasCurrentTrack: Boolean = true,
    ): Result {
        if (action != SleepTimerCommands.ACTION_SET && action != SleepTimerCommands.ACTION_GET)
            return Result.NOT_SUPPORTED
        if (!trusted) return Result.PERMISSION_DENIED
        if (action == SleepTimerCommands.ACTION_GET) {
            timer.tick()
            return Result.SUCCESS
        }
        if (mode !is String) return Result.BAD_VALUE
        if (mode == SleepTimerCommands.MODE_DURATION && durationMs !is Long) return Result.BAD_VALUE
        if (mode == SleepTimerCommands.MODE_END_OF_TRACK && !hasCurrentTrack) return Result.INVALID_STATE
        return if (timer.command(mode, durationMs as? Long ?: 0L, hasCurrentTrack))
            Result.SUCCESS else Result.BAD_VALUE
    }
}
