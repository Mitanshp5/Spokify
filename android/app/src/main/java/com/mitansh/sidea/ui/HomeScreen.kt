package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    greeting: String, recentlyPlayed: List<Track>, favorites: List<Track>, offlineCount: Int,
    onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit,
    onDownload: (Track) -> Unit, onAddToPlaylist: (Track) -> Unit, onHide: (Track) -> Unit = {},
    onOpenFavorites: () -> Unit, onOpenDownloads: () -> Unit, onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier, onOpenProfile: () -> Unit = {},
    onRepeatCount: Int = 0, onOpenOnRepeat: () -> Unit = {},
    playlists: List<PlaylistCard> = emptyList(), onOpenPlaylist: (PlaylistCard) -> Unit = {},
) {
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    LazyColumn(modifier.fillMaxSize().background(SpokifyInk), contentPadding = PaddingValues(bottom = 16.dp + LocalBottomContentPadding.current)) {
        stickyHeader(key = "filters", contentType = "filters") {
            Row(Modifier.fillMaxWidth().background(SpokifyInk).horizontalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileButton(onOpenProfile)
                HomePill("All", true) {}
                HomePill("Liked Songs", false, onOpenFavorites)
                HomePill("Downloaded", false, onOpenDownloads)
            }
        }
        item(key = "shortcuts", contentType = "shortcuts") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(4.dp)).background(SpokifyElevated).motionClickable(onClick = onOpenOnRepeat), verticalAlignment = Alignment.CenterVertically) {
                        OnRepeatArtwork(Modifier.size(56.dp), compact = true)
                        Text("On Repeat", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp))
                    }
                    Shortcut("Liked Songs", Modifier.weight(1f), R.drawable.ic_favorite, onClick = onOpenFavorites)
                }
                playlists.take(4).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { playlist -> Shortcut(playlist.name, Modifier.weight(1f), artwork = playlist.artworkUrl, onClick = { onOpenPlaylist(playlist) }) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                recentlyPlayed.take(if (playlists.isEmpty()) 4 else 2).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { track -> Shortcut(track.title, Modifier.weight(1f), artwork = track.artworkUrl, onClick = { onPlay(track) }) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "made-for-you-title", contentType = "heading") { SectionTitle("Made for you") }
        item(key = "on-repeat", contentType = "recommendation") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                .background(SpokifyElevated).motionClickable(onClick = onOpenOnRepeat).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OnRepeatArtwork(Modifier.size(104.dp), compact = true)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("On Repeat", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("The songs you keep coming back to.", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(if (onRepeatCount > 0) "$onRepeatCount songs · Made for you" else "Made from your listening", color = SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (recentlyPlayed.isNotEmpty()) {
            item(key = "jump-back-title", contentType = "heading") { SectionTitle("Jump back in") }
            item(key = "jump-back-carousel", contentType = "carousel") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(recentlyPlayed.take(8), key = { it.uid }, contentType = { "album" }) { track ->
                        Column(Modifier.width(160.dp).motionClickable { onPlay(track) }) {
                            Artwork(track.artworkUrl, "${track.title} artwork", Modifier.size(160.dp).clip(RoundedCornerShape(5.dp)), sizePx = 640)
                            Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
                            Text(track.artist, color = SpokifyMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "recent-title", contentType = "heading") { SectionTitle("Recently played") }
            items(recentlyPlayed, key = { "recent:${it.uid}" }, contentType = { "song" }) { track ->
                SongRow(track, track.uid in favoriteUids, { onPlay(track) }, { onFavorite(track) },
                    onDownload = { onDownload(track) }, onAddToPlaylist = { onAddToPlaylist(track) }, onHide = { onHide(track) })
            }
        } else {
            item(key = "empty", contentType = "empty") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 40.dp)) {
                    Text(greeting, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Your next favourite is waiting.", color = SpokifyMuted, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.material3.Button(onClick = onOpenSearch, modifier = Modifier.padding(top = 20.dp)) { Text("Find your music") }
                }
            }
        }
    }
}

@Composable
fun HomePill(title: String, selected: Boolean = false, onClick: () -> Unit) {
    val foreground by animateColorAsState(if (selected) Color.Black else Color.White, motionTween(160), label = "filter text")
    val background by animateColorAsState(if (selected) SpokifyGreen else Color.White.copy(alpha = .09f), motionTween(160), label = "filter fill")
    Text(title, color = foreground, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(background).motionClickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 10.dp))
}

@Composable
fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 16.dp))
}

@Composable
private fun Shortcut(title: String, modifier: Modifier, icon: Int? = null, artwork: String? = null, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 58.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .09f)).motionClickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(56.dp).background(Brush.linearGradient(if (icon == R.drawable.ic_favorite) listOf(Color(0xFF4824B8), Color(0xFFAFD9CF)) else listOf(Color(0xFF16634F), Color(0xFF213B31)))), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(25.dp), tint = Color.White)
            }
        } else Artwork(artwork, null, Modifier.size(56.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp))
    }
}
