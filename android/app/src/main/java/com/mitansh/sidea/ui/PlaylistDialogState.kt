package com.mitansh.sidea.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.mitansh.sidea.data.library.PlaylistRepository

internal data class PlaylistDialogOperation(val action: String, val playlistId: String?, val name: String) {
    suspend fun commitTo(playlists: PlaylistRepository) {
        when (action) {
            "create" -> playlists.create(name)
            "rename" -> playlists.rename(requireNotNull(playlistId), name)
            "delete" -> playlists.deletePlaylist(requireNotNull(playlistId))
        }
    }
}

/** Shared orchestration for the playlist management dialog. */
internal class PlaylistDialogState(val action: String, val playlistId: String?) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var closed = false

    fun canConfirm(draft: String): Boolean = !closed && !busy &&
        (action == "create" || playlistId != null) && (action == "delete" || draft.isNotBlank())

    fun dismiss(onDismiss: () -> Unit) {
        if (busy || closed) return
        closed = true
        onDismiss()
    }

    fun confirm(scope: CoroutineScope, draft: String,
        commit: suspend (PlaylistDialogOperation) -> Unit, onSuccess: () -> Unit) {
        if (!canConfirm(draft)) return
        val operation = PlaylistDialogOperation(action, playlistId, draft.trim())
        // Acquire before launching: two taps can arrive before the coroutine gets a turn.
        busy = true
        error = null
        scope.launch {
            try {
                commit(operation)
                closed = true
                onSuccess()
            } catch (cancel: CancellationException) {
                error = "Changes weren't confirmed. Try again."
                throw cancel
            } catch (_: Exception) {
                error = if (action == "delete") "Couldn't delete playlist. Try again."
                    else "Couldn't save playlist. Try again."
            } finally {
                busy = false
            }
        }
    }
}
