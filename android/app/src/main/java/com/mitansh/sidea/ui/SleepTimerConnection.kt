package com.mitansh.sidea.ui

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.mitansh.sidea.playback.SleepTimerCommands
import java.util.concurrent.Executor
import kotlinx.coroutines.delay

/** Polls only status while STARTED. Playback expiry belongs exclusively to PlaybackService. */
@Composable
internal fun rememberSleepTimer(controller: MediaController?): SleepTimerConnection {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connection = remember(controller) { SleepTimerConnection(controller, ContextCompat.getMainExecutor(context)) }
    DisposableEffect(connection) { onDispose { connection.dispose() } }
    LaunchedEffect(connection, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                connection.refresh()
                delay(1_000L)
            }
        }
    }
    return connection
}

internal class SleepTimerConnection(private val controller: MediaController?, private val executor: Executor) {
    private val requests = SleepTimerRequests()
    var state by mutableStateOf(requests.state)
        private set

    fun refresh() = request(null)
    fun setMinutes(minutes: Int) {
        if (minutes !in 1..180) return
        request(Bundle().apply {
            putString(SleepTimerCommands.KEY_MODE, SleepTimerCommands.MODE_DURATION)
            putLong(SleepTimerCommands.KEY_DURATION_MS, minutes * 60_000L)
        })
    }
    fun endOfSong() = setMode(SleepTimerCommands.MODE_END_OF_TRACK)
    fun cancel() = setMode(SleepTimerCommands.MODE_OFF)
    private fun setMode(mode: String) = request(Bundle().apply { putString(SleepTimerCommands.KEY_MODE, mode) })

    private fun request(args: Bundle?) {
        val token = requests.begin(setting = args != null) ?: return
        state = requests.state
        fun finish(success: Boolean, status: SleepTimerStatus?, error: String? = null) {
            if (requests.complete(token, success, status, error)) state = requests.state
        }
        if (controller == null || !controller.isConnected) {
            finish(false, null, "Sleep timer disconnected. Reopen the player to reconnect.")
            return
        }
        try {
            val future = controller.sendCustomCommand(if (args == null) SleepTimerCommands.GET else SleepTimerCommands.SET, args ?: Bundle.EMPTY)
            future.addListener({
                try {
                    // addListener runs only after completion; never blocks the main thread.
                    val result = future.get()
                    finish(result.resultCode == SessionResult.RESULT_SUCCESS, result.extras.sleepTimerStatus(), when (result.resultCode) {
                        SessionResult.RESULT_SUCCESS -> null
                        SessionResult.RESULT_ERROR_INVALID_STATE -> "No current song. Play a song first."
                        SessionResult.RESULT_ERROR_BAD_VALUE -> "Couldn't set sleep timer. Choose 1–180 minutes."
                        SessionResult.RESULT_ERROR_PERMISSION_DENIED -> "Sleep timer access denied. Reopen the player."
                        else -> "Couldn't update sleep timer (code ${result.resultCode}). Try again."
                    })
                } catch (_: Exception) {
                    finish(false, null, "Couldn't read sleep timer. Try again.")
                }
            }, executor)
        } catch (_: Exception) {
            finish(false, null, "Couldn't contact sleep timer. Try again.")
        }
    }

    fun dispose() = requests.dispose()
}

@Suppress("DEPRECATION") // Bundle.get preserves actual types; typed getters silently default malformed fields.
private fun Bundle.sleepTimerStatus(): SleepTimerStatus? = sleepTimerStatus(
    get(SleepTimerCommands.KEY_MODE), get(SleepTimerCommands.KEY_DEADLINE_MS),
    get(SleepTimerCommands.KEY_REMAINING_MS), get(SleepTimerCommands.KEY_END_OF_TRACK),
)
