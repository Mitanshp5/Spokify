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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.PlaylistTrackEntry

@Composable
fun CollectionScreen(title: String, onBack: () -> Unit, songs: List<Track>, favorites: List<Track>, onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit, onDownload: (Track) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ScreenHeader(title, onBack)
        Text("${songs.size} songs", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        SongList(songs, favorites, onPlay, onFavorite, onDownload = onDownload, modifier = Modifier.weight(1f), emptyMessage = "Nothing here yet.")
    }
}

@Composable
fun PlaylistScreen(
    name: String, entries: List<PlaylistTrackEntry>, favorites: List<Track>, onBack: () -> Unit,
    onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit, onDownload: (Track) -> Unit,
    onRemove: (PlaylistTrackEntry) -> Unit, onMove: (PlaylistTrackEntry, Boolean) -> Unit,
    onDownloadAll: (List<Track>) -> Unit = {}, onRename: () -> Unit, onDelete: () -> Unit,
    onPlayAll: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    LazyColumn(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF343744), SpokifySurface), endY = 1000f)), contentPadding = PaddingValues(bottom = 20.dp + LocalBottomContentPadding.current)) {
        item(key = "playlist-header", contentType = "playlist-header") {
            ScreenHeader("", onBack, actions = {
                Box {
                    MotionIconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), "Playlist options", tint = Color.White) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; onDelete() })
                    }
                }
            })
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Artwork(entries.firstOrNull()?.artworkUrl, "$name artwork", Modifier.size(180.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(4.dp)), sizePx = 640)
                Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
                Text("${entries.size} songs · Spokify", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onDownloadAll(entries.map { it.track }) }, enabled = entries.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Text("Download playlist")
                    }
                    Spacer(Modifier.weight(1f))
                    MotionFilledIconButton(onClick = onPlayAll, enabled = entries.isNotEmpty(), modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) { Icon(painterResource(R.drawable.ic_play), "Play playlist", Modifier.size(26.dp)) }
                }
            }
        }
        if (entries.isEmpty()) item { Text("Add some music to make it yours.", color = SpokifyMuted, modifier = Modifier.padding(24.dp)) }
        items(entries, key = { it.entryId }, contentType = { "song" }) { entry ->
            SongRow(entry.track, entry.track.uid in favoriteUids, { onPlay(entry.track) }, { onFavorite(entry.track) }, onDownload = { onDownload(entry.track) }, modifier = Modifier.animateItem(), extraActions = {
                SheetAction("Move up", R.drawable.ic_chevron_down) { onMove(entry, true) }
                SheetAction("Move down", R.drawable.ic_chevron_down) { onMove(entry, false) }
                SheetAction("Remove from this playlist", R.drawable.ic_close) { onRemove(entry) }
            })
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        MotionIconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Go back", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}
