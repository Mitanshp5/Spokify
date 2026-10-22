package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.offline.OfflineItem

@Composable
fun OfflineReadyScreen(
    items: List<OfflineItem>, favorites: List<Track>, onPlay: (Track) -> Unit,
    onPlayAll: (List<Track>) -> Unit, onFavorite: (Track) -> Unit, onRemove: (String) -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier, onSettings: () -> Unit = {},
    offline: Boolean = false,
) {
    val ready = remember(items) { items.filter { it.isComplete } }
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    LazyColumn(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF205744), SpokifySurface), endY = 1100f)), contentPadding = PaddingValues(bottom = 20.dp + LocalBottomContentPadding.current)) {
        item {
            ScreenHeader("", onBack, actions = { MotionIconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), "Manage downloads", tint = Color.White) } })
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(172.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(listOf(Color(0xFF2AAB7B), Color(0xFF0F3A2F)))), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_downloaded), null, Modifier.size(68.dp), tint = Color(0xFFBFFFE0))
            }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Offline Backup", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Your music, even without a connection.", color = Color(0xFFBECFC6), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Text("${ready.size} songs · ${formatBytes(ready.sumOf { it.sizeBytes })}" + if (offline) " · You're offline" else "", color = Color(0xFFBECFC6), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_brand), null, Modifier.size(24.dp), tint = Color.Unspecified)
                    Text("Spokify", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    MotionFilledIconButton(onClick = { onPlayAll(ready.map { it.track }) }, enabled = ready.isNotEmpty(), modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) { Icon(painterResource(R.drawable.ic_play), "Play Offline Backup", Modifier.size(26.dp)) }
                }
            }
        }
        if (ready.isEmpty()) item { Text("Songs you download and the music saved automatically will appear here, ready to play.", color = SpokifyMuted, modifier = Modifier.padding(24.dp)) }
        items(ready, key = { it.id }, contentType = { "song" }) { item ->
            SongRow(item.track, item.track.uid in favoriteUids, { onPlay(item.track) }, { onFavorite(item.track) }, onDownload = {})
        }
    }
}
