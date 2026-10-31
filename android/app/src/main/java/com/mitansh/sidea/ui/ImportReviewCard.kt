package com.mitansh.sidea.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportedItem

/** Source and playable recording are always visually distinct; covers never stand in for identity. */
@Composable
internal fun ImportReviewCard(
    item: ImportedItem,
    chosen: Track?,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onChoose: (Track) -> Unit,
) {
    val candidates = importReviewCandidates(item)
    val status = when {
        chosen != null -> "Ready · Selected"
        item.status == ImportedItem.Status.REVIEW -> "Needs review"
        else -> "Not found"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SpokifySurface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Song ${item.position + 1} · $status",
                color = if (chosen != null) SpokifyGreen else Color(0xFFFFD180),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            ImportReviewRecording("Source", item.sourceArtworkUrl, item.title, item.artist, item.durationMs)
            if (chosen != null && !expanded) {
                ImportReviewRecording("Selected recording", chosen.artworkUrl, chosen.title, chosen.artist, chosen.durationMs)
            }
            if (candidates.isNotEmpty()) {
                TextButton(
                    onClick = onToggle,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (expanded) "Hide matches" else if (chosen != null) "Change match" else "Compare matches", color = SpokifyGreen)
                }
            } else if (chosen == null) {
                Text(
                    if (item.title.isBlank()) "This source track is unavailable. No recording could be compared; it will not be saved."
                    else "No matching recordings were returned. This song will not be saved. Read the playlist again to retry matching.",
                    color = SpokifyMuted, fontSize = 13.sp,
                )
            }
            if (expanded) {
                Text("Compare the cover, artist and duration, then choose a recording.", color = SpokifyMuted, fontSize = 13.sp)
                candidates.forEach { candidate ->
                    val isSelected = candidate.uid == chosen?.uid
                    Surface(
                        onClick = { onChoose(candidate) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = SpokifyElevated,
                        border = BorderStroke(1.dp, if (isSelected) SpokifyGreen else Color(0xFF444444)),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (isSelected) "Selected" else "Choose this recording",
                                color = if (isSelected) SpokifyGreen else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                            ImportReviewRecording(null, candidate.artworkUrl, candidate.title, candidate.artist, candidate.durationMs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportReviewRecording(label: String?, artwork: String?, title: String, artist: String, durationMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, color = SpokifyMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(
                artwork, if (artwork.isNullOrBlank()) "No cover available" else "Cover for ${importReviewTitle(title)}",
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(importReviewTitle(title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artist.ifBlank { "Artist unknown" }, color = SpokifyMuted, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(importReviewDuration(durationMs), color = SpokifyMuted, fontSize = 12.sp)
            }
        }
    }
}
