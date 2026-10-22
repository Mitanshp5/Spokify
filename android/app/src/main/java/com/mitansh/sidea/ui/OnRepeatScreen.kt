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

private val RepeatColors = listOf(Color(0xFF243375), Color(0xFF202E68))

@Composable
fun OnRepeatArtwork(modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(RepeatColors)), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_on_repeat), null, Modifier.fillMaxSize(.78f), tint = Color.Unspecified)
        if (!compact) Text("On\nRepeat", color = Color.White, fontSize = 15.sp, lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

@Composable
fun OnRepeatScreen(
    songs: List<Track>, favorites: List<Track>, onBack: () -> Unit, onPlay: (Track) -> Unit,
    onPlayAll: () -> Unit, onShuffle: () -> Unit, onFavorite: (Track) -> Unit,
    onDownload: (Track) -> Unit, onDownloadAll: () -> Unit, onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    LazyColumn(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF243375), SpokifyInk), endY = 1100f)), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomContentPadding.current)) {
        item {
            ScreenHeader("", onBack)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_on_repeat), null, Modifier.size(210.dp), tint = Color.Unspecified)
                Text("On Repeat", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Songs you love right now", color = SpokifyMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                Text("Made for you · ${songs.size} songs · Updates automatically", color = Color(0xFFD4C3CE), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val downloads = LocalSongEnvironment.current.downloads
                    val ready = songs.count { downloads[it.id]?.isComplete == true }
                    val saving = songs.any { downloads[it.id]?.status in setOf(com.mitansh.sidea.data.offline.OfflineStatus.QUEUED, com.mitansh.sidea.data.offline.OfflineStatus.DOWNLOADING, com.mitansh.sidea.data.offline.OfflineStatus.WAITING) }
                    val label = when { songs.isNotEmpty() && ready == songs.size -> "Downloaded"; saving -> "Saving $ready/${songs.size}"; else -> "Download" }
                    TextButton(onClick = onDownloadAll, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(if (songs.isNotEmpty() && ready == songs.size) R.drawable.ic_downloaded else R.drawable.ic_download), "$label On Repeat", tint = if (ready == songs.size && songs.isNotEmpty()) SpokifyGreen else SpokifyMuted)
                        Text(label, color = SpokifyMuted, modifier = Modifier.padding(start = 8.dp), fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionIconButton(onClick = onShuffle, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_shuffle), "Shuffle On Repeat", tint = if (songs.isEmpty()) SpokifyMuted else Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    MotionFilledIconButton(onClick = onPlayAll, enabled = songs.isNotEmpty(), modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) {
                        Icon(painterResource(R.drawable.ic_play), "Play On Repeat", Modifier.size(28.dp))
                    }
                }
            }
        }
        if (songs.isEmpty()) item {
            Text("Keep listening to build your On Repeat. Songs appear after you've listened to them at least twice.", color = SpokifyMuted, modifier = Modifier.padding(24.dp))
        }
        items(songs, key = { it.uid }, contentType = { "song" }) { track ->
            SongRow(track, track.uid in favoriteUids, { onPlay(track) }, { onFavorite(track) },
                onDownload = { onDownload(track) }, onAddToPlaylist = { onAddToPlaylist(track) }, modifier = Modifier.animateItem())
        }
    }
}
