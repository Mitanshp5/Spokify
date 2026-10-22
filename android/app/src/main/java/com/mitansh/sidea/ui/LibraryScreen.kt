package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R

data class PlaylistCard(val id: String, val name: String, val songCount: Int, val artworkUrl: String?, val createdAtEpochMs: Long = 0)

@Composable
fun LibraryScreen(
    playlists: List<PlaylistCard>, favoriteCount: Int, historyCount: Int, downloadCount: Int,
    onOpenPlaylist: (PlaylistCard) -> Unit, onOpenFavorites: () -> Unit, onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit, onOpenOfflineReady: () -> Unit,
    onOpenImport: () -> Unit, onOpenProfile: () -> Unit, onOpenSettings: () -> Unit,
    onCreatePlaylist: () -> Unit, modifier: Modifier = Modifier,
    onRepeatCount: Int = 0, onOpenOnRepeat: () -> Unit = {},
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var nameSort by rememberSaveable { mutableStateOf(false) }
    var playlistOnly by rememberSaveable { mutableStateOf(false) }
    val visible = remember(playlists, query, nameSort) {
        playlists.filter { it.name.contains(query, true) }.let { rows ->
            if (nameSort) rows.sortedBy { it.name.lowercase() } else rows.sortedByDescending { it.createdAtEpochMs }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileButton(onOpenProfile)
            Text("Your Library", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            MotionIconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(painterResource(R.drawable.ic_search), "Search your library", tint = Color.White) }
            MotionIconButton(onClick = onCreatePlaylist) { Icon(painterResource(R.drawable.ic_add), "Create playlist", tint = Color.White) }
        }
        if (searching) OutlinedTextField(query, { query = it }, label = { Text("Search your library") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomePill("Playlists", playlistOnly) { playlistOnly = !playlistOnly }
            HomePill("Downloaded", false, onOpenOfflineReady)
            HomePill("Import", false, onOpenImport)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nameSort = !nameSort }) {
                Icon(painterResource(R.drawable.ic_history), null, Modifier.size(18.dp), tint = Color.White)
                Text(if (nameSort) "Name" else "Recently added", color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            MotionIconButton(onClick = { grid = !grid }) { Icon(painterResource(if (grid) R.drawable.ic_library else R.drawable.ic_grid), if (grid) "Show list" else "Show grid", tint = Color.White) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp + LocalBottomContentPadding.current)) {
            if ("Liked Songs".contains(query, true)) item { LibraryRow("Liked Songs", "Playlist · $favoriteCount songs", R.drawable.ic_favorite, listOf(Color(0xFF4A27BA), Color(0xFFB4DCC9)), onOpenFavorites) }
            if ("On Repeat".contains(query, true)) item {
                Row(Modifier.fillMaxWidth().motionClickable(onClick = onOpenOnRepeat).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    OnRepeatArtwork(Modifier.size(64.dp), compact = true)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("On Repeat", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Made for you · $onRepeatCount songs", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (!playlistOnly && "Offline Backup".contains(query, true)) item { LibraryRow("Offline Backup", "$downloadCount songs ready to play", R.drawable.ic_downloaded, listOf(Color(0xFF176A53), Color(0xFF203D32)), onOpenOfflineReady) }
            if (!playlistOnly && "Recently played".contains(query, true)) item { LibraryRow("Recently played", "$historyCount songs", R.drawable.ic_music, listOf(Color(0xFF775D30), Color(0xFF272421)), onOpenHistory) }
            if (grid) items(visible.chunked(2), key = { "grid:${it.first().id}" }, contentType = { "playlist-pair" }) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { playlist ->
                        Column(Modifier.weight(1f).motionClickable { onOpenPlaylist(playlist) }) {
                            Artwork(playlist.artworkUrl, "${playlist.name} artwork", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(5.dp)), sizePx = 640)
                            Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Playlist · ${playlist.songCount} songs", color = SpokifyMuted, fontSize = 12.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            } else items(visible, key = { it.id }, contentType = { "playlist" }) { playlist ->
                Row(Modifier.animateItem().fillMaxWidth().motionClickable { onOpenPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(playlist.artworkUrl, "${playlist.name} artwork", Modifier.size(64.dp).clip(RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(playlist.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Playlist · ${playlist.songCount} songs", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                TextButton(onClick = onCreatePlaylist, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) { Icon(painterResource(R.drawable.ic_add), null); Spacer(Modifier.width(12.dp)); Text("Create a playlist") }
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, icon: Int, colors: List<Color>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(3.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(28.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
