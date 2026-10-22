package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SaveToPlaylistSheet(
    track: Track, liked: Boolean, memberships: Set<String>, playlists: List<Playlist>,
    counts: Map<String, Int>, artwork: Map<String, String?>,
    onCommit: suspend (Boolean, Boolean, Set<String>, Set<String>) -> Unit,
    onCreate: suspend (String) -> String, onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initialLiked = remember(track.uid) { liked }
    var initialIds by remember(track.uid) { mutableStateOf(memberships) }
    var selectedLiked by remember(track.uid) { mutableStateOf(liked) }
    var selectedIds by remember(track.uid) { mutableStateOf(memberships) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }

    fun commit(dismiss: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                onCommit(initialLiked, selectedLiked, initialIds, selectedIds)
                dismiss()
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { error = "Couldn't save changes. Try again."
            } finally { busy = false }
        }
    }

    MotionBottomSheet(onDismiss = { if (!busy) onDismiss() }) { dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (creating) creating = false else dismiss() }, enabled = !busy) { Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold) }
                if (!creating) TextButton(onClick = { commit(dismiss) }, enabled = !busy) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (creating) "New playlist" else "Save in", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!creating) TextButton(enabled = !busy, onClick = { creating = true; error = null }) { Text("New playlist", color = SpokifyGreen, fontWeight = FontWeight.Bold) }
            }
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = SpokifyError, modifier = Modifier.padding(top = 12.dp)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { creating = false; error = null }, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        busy = true
                        error = null
                        val playlistName = name.trim()
                        scope.launch {
                            try {
                                val id = onCreate(playlistName)
                                // Creating explicitly saves the song; later Cancel only discards other pending edits.
                                initialIds = initialIds + id
                                selectedIds = selectedIds + id
                                confirmation = "Created $playlistName and added this song"
                                query = ""
                                creating = false
                                name = ""
                            } catch (cancel: CancellationException) { throw cancel
                            } catch (_: Exception) { error = "Couldn't create playlist. Try again."
                            } finally { busy = false }
                        }
                    }, enabled = !busy && name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) {
                        Text(if (busy) "Creating…" else "Create")
                    }
                }
            } else {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Find a playlist") }, singleLine = true,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if ("Liked Songs".contains(query, ignoreCase = true)) item {
                        SaveDestinationRow("Liked Songs", "Your favorite songs", selectedLiked, !busy, {
                            selectedLiked = !selectedLiked
                        }) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF4A32B8), Color(0xFFB4D9C4)))), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_favorite), null, Modifier.size(22.dp), tint = Color.White)
                            }
                        }
                    }
                    val filtered = playlists.filter { it.name.contains(query, ignoreCase = true) }
                    items(filtered, key = { it.id }) { playlist ->
                        SaveDestinationRow(playlist.name, "${counts[playlist.id] ?: 0} songs", playlist.id in selectedIds, !busy, {
                            selectedIds = if (playlist.id in selectedIds) selectedIds - playlist.id else selectedIds + playlist.id
                        }) { Artwork(artwork[playlist.id], null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) }
                    }
                    if (filtered.isEmpty() && query.isNotEmpty() && !"Liked Songs".contains(query, true)) item {
                        Text("No playlists found", color = SpokifyMuted, modifier = Modifier.padding(vertical = 20.dp))
                    }
                }
                confirmation?.let { Text(it, color = SpokifyGreen, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                error?.let { Text(it, color = SpokifyError, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { commit(dismiss) }, enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) {
                        Text(if (busy) "Saving…" else "Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveDestinationRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    onToggle: () -> Unit, cover: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).motionClickable(enabled = enabled, onClick = onToggle)
        .semantics(mergeDescendants = true) {
            stateDescription = if (selected) "Selected" else "Not selected"
            role = Role.Checkbox
            toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
        }, verticalAlignment = Alignment.CenterVertically) {
        cover()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SpokifyMuted, fontSize = 12.sp)
        }
        SaveIcon(selected, Modifier.padding(12.dp).size(24.dp), description = if (selected) "Selected" else "Add to this playlist")
    }
}
