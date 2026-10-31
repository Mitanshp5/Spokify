package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R

@Composable
fun QueueSheet(snapshot: QueueSnapshot, onDismiss: () -> Unit, onAction: (QueueAction) -> Unit) {
    val presentation = snapshot.presentation
    val scroll = rememberLazyListState()
    val rowKeys = remember { QueueRowKeys() }
    // Current is always the first row: no long scroll through already-played songs.
    LaunchedEffect(presentation.current?.instanceId) { scroll.scrollToItem(0) }
    MotionBottomSheet(onDismiss = onDismiss) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(QueueAction.ClearUpcoming) }, enabled = presentation.upcoming.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Clear upcoming")
            }
        }
        val knownDuration = presentation.upcoming.all { it.durationMs > 0 }
        val remaining = presentation.upcoming.sumOf { it.durationMs.coerceAtLeast(0) }
        Text("${presentation.upcoming.size} songs up next" + if (knownDuration && remaining > 0) " · ${formatPlaybackTime(remaining)}" else "",
            color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (snapshot.shuffled) Text("Shuffle order · Turn shuffle off to reorder", color = SpokifyMuted,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("queue-list"), state = scroll,
            contentPadding = PaddingValues(bottom = 24.dp)) {
            presentation.current?.let { current ->
                item(key = "now-playing") {
                    QueueSection("Now playing")
                    QueueSong(current, true) { onAction(QueueAction.Select(current)) }
                    QueueSection("Up next")
                }
            }
            if (presentation.current == null) {
                item { Text("Your queue is empty", color = Color.White, modifier = Modifier.padding(24.dp)) }
            } else if (presentation.upcoming.isEmpty()) {
                item { Text("You're at the end of the queue", color = SpokifyMuted, modifier = Modifier.padding(24.dp)) }
            }
            itemsIndexed(presentation.upcoming, key = { _, entry -> rowKeys.key(entry.instanceId) }) { position, entry ->
                Column {
                    QueueSong(entry, false) { onAction(QueueAction.Select(entry)) }
                    Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        QueueMoveButton(entry, up = true, enabled = !snapshot.shuffled && position > 0) { onAction(QueueAction.Move(entry, true)) }
                        QueueMoveButton(entry, up = false, enabled = !snapshot.shuffled && position < presentation.upcoming.lastIndex) { onAction(QueueAction.Move(entry, false)) }
                        TextButton(onClick = { onAction(QueueAction.Remove(entry)) }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Remove ${entry.title}, queue position ${entry.index + 1}" }) {
                            Text("Remove", color = SpokifyMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSection(label: String) {
    Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

@Composable
private fun QueueSong(entry: QueueEntry, current: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("queue-row-${entry.index}")
        .background(if (current) SpokifyGreen.copy(alpha = .08f) else Color.Transparent)
        .motionClickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 8.dp)
        .semantics { contentDescription = "${if (current) "Resume" else "Play"} ${entry.title}, queue position ${entry.index + 1}" },
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(entry.artworkUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, color = if (current) SpokifyGreen else Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.artist, color = SpokifyMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Icon(painterResource(R.drawable.ic_music), "Now playing", tint = SpokifyGreen, modifier = Modifier.size(20.dp))
        else if (entry.durationMs > 0) Text(formatPlaybackTime(entry.durationMs), color = SpokifyMuted, fontSize = 12.sp)
    }
}

@Composable
private fun QueueMoveButton(entry: QueueEntry, up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_chevron_down), "Move ${entry.title} ${if (up) "up" else "down"}, queue position ${entry.index + 1}",
            modifier = Modifier.size(24.dp).rotate(if (up) 180f else 0f), tint = if (enabled) Color.White else SpokifyMuted.copy(alpha = .35f))
    }
}
