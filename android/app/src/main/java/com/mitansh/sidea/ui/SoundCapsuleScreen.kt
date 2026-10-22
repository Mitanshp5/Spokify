package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.MonthlyListening

@Composable
fun SoundCapsuleScreen(summary: MonthlyListening, favorites: List<Track>, onBack: () -> Unit, onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit, onDownload: (Track) -> Unit, topSongs: Boolean = false, onOpenTopSongs: () -> Unit = {}) {
    val month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    LazyColumn(Modifier.fillMaxSize().background(SpokifyInk), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomContentPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(if (topSongs) "Top songs" else "Your Sound Capsule", onBack) }
        item { Text(month, color = SpokifyMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (!topSongs) {
            item {
                Surface(color = SpokifyElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).motionClickable(onClick = onOpenTopSongs)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Qualified listens", color = SpokifyMuted, fontSize = 15.sp)
                        Text("${summary.plays} plays", color = SpokifyGreen, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${summary.songs.size} different songs this month", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = SpokifyElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).heightIn(min = 180.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Top artist", color = SpokifyMuted)
                            Text(summary.artists.firstOrNull()?.first ?: "Keep listening", color = Color(0xFF79B6FF), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            summary.artists.firstOrNull()?.let { Text("${it.second} plays", color = SpokifyMuted, modifier = Modifier.padding(top = 12.dp)) }
                        }
                    }
                    Surface(color = SpokifyElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).motionClickable(onClick = onOpenTopSongs)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Top song", color = SpokifyMuted)
                            Text(summary.songs.firstOrNull()?.track?.title ?: "Your next favourite", color = Color(0xFFF2EB6D), fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                            summary.songs.firstOrNull()?.let { Artwork(it.track.artworkUrl, null, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 12.dp).clip(RoundedCornerShape(5.dp)), sizePx = 640) }
                        }
                    }
                }
            }
            item { Text("Counts come from qualified listening recorded on this device. Listening minutes aren't available for older sessions.", color = SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
            item { Text("Your top songs", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp)) }
        } else item { Text("You played ${summary.songs.size} different songs this month.", color = Color(0xFFF2EB6D), fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (summary.songs.isEmpty()) item { Text("Start listening to build your Sound Capsule.", color = SpokifyMuted, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(summary.songs, key = { _, song -> song.track.uid }, contentType = { _, _ -> "ranked-song" }) { index, song ->
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Color(0xFFF2EB6D), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(if (song.plays == 1) "1 play" else "${song.plays} plays", color = SpokifyMuted, modifier = Modifier.padding(start = 16.dp))
                }
                SongRow(song.track, song.track.uid in favoriteUids, { onPlay(song.track) }, { onFavorite(song.track) }, onDownload = { onDownload(song.track) })
            }
        }
    }
}
