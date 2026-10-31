package com.mitansh.sidea.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportedItem
import com.mitansh.sidea.data.imports.ImportSessionStore

/**
 * Import a playlist from a link.
 *
 * The flow is deliberately short: paste a link, see how much of the playlist was read, review the
 * rows that need attention, then save. The same flow handles a Spotify link and a YouTube link.
 */
@Composable
fun ImportScreen(
    loading: Boolean,
    progress: String?,
    session: ImportSessionStore.Session?,
    error: String?,
    savedMessage: String?,
    onFetch: (String) -> Unit,
    onSave: (Map<Int, Track>) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    var link by remember { mutableStateOf("") }
    var selections by remember(session?.id) { mutableStateOf<Map<Int, Track>>(emptyMap()) }
    var filter by remember(session?.id) { mutableStateOf(ImportReviewFilter.ATTENTION) }
    var expandedPositions by remember(session?.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var choiceMessage by remember(session?.id) { mutableStateOf<String?>(null) }

    Column(modifier.padding(bottom = LocalBottomContentPadding.current).padding(horizontal = 16.dp)) {
        ScreenHeader(title = "Import a playlist", onBack = onBack)
        if (session == null) {
            OutlinedTextField(
                link, { link = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text("Spotify or YouTube playlist link") }, singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onFetch(link) },
                    enabled = link.isNotBlank() && !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen),
                ) { Text("Read playlist") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = SpokifyGreen, strokeWidth = 2.dp)
                    Text(progress ?: "Reading…", color = SpokifyMuted, modifier = Modifier.padding(start = 12.dp))
                }
            }
            error?.let { Text(it, color = SpokifyError, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            Text(
                "Paste a public Spotify or YouTube playlist link. Review the song matches before saving.",
                color = SpokifyMuted, fontSize = 13.sp,
            )
        } else {
            val readyCount = importReviewItems(session.items, selections, ImportReviewFilter.READY).size
            val attentionCount = session.items.size - readyCount
            val visibleItems = importReviewItems(session.items, selections, filter)
            LazyColumn(Modifier.weight(1f)) {
                item(key = "review-summary") {
                    Text(session.title.ifBlank { "Playlist" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(completenessLabel(session, selections.keys), color = SpokifyMuted, fontSize = 13.sp)
                    session.warning?.let { Text(it, color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    Text("Check the source against each recording before saving.", color = SpokifyMuted,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        ImportReviewFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                modifier = Modifier.padding(end = 8.dp),
                                label = { Text(when (option) {
                                    ImportReviewFilter.ATTENTION -> "Needs attention ($attentionCount)"
                                    ImportReviewFilter.READY -> "Ready ($readyCount)"
                                    ImportReviewFilter.ALL -> "All (${session.items.size})"
                                }) },
                            )
                        }
                    }
                    choiceMessage?.let { Text(it, color = SpokifyGreen, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                if (visibleItems.isEmpty()) {
                    item(key = "review-empty") {
                        Text(
                            if (filter == ImportReviewFilter.ATTENTION) "All read songs are ready. Open Ready to check or change a match."
                            else "No songs here yet. Choose a recording in Needs attention.",
                            color = SpokifyMuted, modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(visibleItems, key = { it.position }) { item ->
                    ImportReviewCard(
                        item = item,
                        chosen = importReviewChoice(item, selections),
                        expanded = item.position in expandedPositions,
                        enabled = !loading,
                        onToggle = {
                            expandedPositions = if (item.position in expandedPositions) expandedPositions - item.position
                            else expandedPositions + item.position
                        },
                        onChoose = {
                            selections = selections + (item.position to it)
                            expandedPositions = expandedPositions - item.position
                            choiceMessage = "Song ${item.position + 1} is ready · ${importReviewTitle(it.title)} selected"
                        },
                    )
                }
            }
            savedMessage?.let { Text(it, color = SpokifyGreen, fontSize = 13.sp) }
            error?.let { Text(it, color = SpokifyError, fontSize = 13.sp) }
            Text(
                if (attentionCount > 0) "$attentionCount songs not saved · still need attention"
                else "All read songs are included",
                color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSave(selections) },
                    modifier = Modifier.weight(1f),
                    enabled = !loading && readyCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    Text(if (loading) "Saving…" else "Save $readyCount songs")
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** An honest, short description of how much of the playlist was actually read. */
fun completenessLabel(session: ImportSessionStore.Session, selectedPositions: Set<Int> = emptySet()): String {
    val read = session.items.size
    val base = when {
        session.complete -> "Read all $read songs"
        session.declaredCount != null -> "Read $read of ${session.declaredCount} songs"
        else -> "Read $read songs · total unknown"
    }
    val unresolved = session.items.filter { it.selected == null && it.position !in selectedPositions }
    val missing = unresolved.count { it.status == ImportedItem.Status.MISSING }
    val review = unresolved.count { it.status == ImportedItem.Status.REVIEW }
    val notes = buildList {
        if (review > 0) add("$review need a check")
        if (missing > 0) add("$missing not found")
    }
    return if (notes.isEmpty()) base else "$base · ${notes.joinToString(", ")}"
}
