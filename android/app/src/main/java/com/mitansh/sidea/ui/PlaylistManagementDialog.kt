package com.mitansh.sidea.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

internal const val PLAYLIST_DELETE_SCOPE = "This removes the playlist, not your songs. Liked songs and downloads are kept."

@Composable
internal fun PlaylistManagementDialog(
    state: PlaylistDialogState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: suspend (PlaylistDialogOperation) -> Unit,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val deleting = state.action == "delete"
    fun confirm() = state.confirm(scope, draft, onCommit, onSuccess)

    LaunchedEffect(state) {
        if (!deleting) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
    }
    AlertDialog(
        onDismissRequest = { state.dismiss(onDismiss) },
        title = { Text(when (state.action) {
            "create" -> "New playlist"
            "rename" -> "Rename playlist"
            else -> "Delete playlist?"
        }) },
        text = {
            Column {
                if (deleting) Text(PLAYLIST_DELETE_SCOPE) else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !state.busy,
                        isError = draft.isNotEmpty() && draft.isBlank(),
                        supportingText = { if (draft.isBlank()) Text("Enter a playlist name.") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = state.canConfirm(draft)) {
                Text(if (state.busy) { if (deleting) "Deleting…" else "Saving…" }
                    else if (deleting) "Delete" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { state.dismiss(onDismiss) }, enabled = !state.busy) { Text("Cancel") }
        },
    )
}
