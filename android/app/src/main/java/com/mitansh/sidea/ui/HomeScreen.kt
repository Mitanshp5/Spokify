package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    activeGroupName: String? = null,
    activeTrackUid: String? = null,
    isPlaying: Boolean = false,
) {
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    val dynamicShortcuts = remember(playlists, recentlyPlayed) {
        buildHomeDynamicShortcuts(playlists, recentlyPlayed)
    }
    val activeShortcutKey = remember(activeGroupName, activeTrackUid, dynamicShortcuts) {
        resolveActiveHomeShortcutKey(activeGroupName, activeTrackUid, dynamicShortcuts)
    }
    LazyColumn(modifier.fillMaxSize().background(SpokifyInk), contentPadding = PaddingValues(bottom = 16.dp + LocalBottomContentPadding.current)) {
        stickyHeader(key = "filters", contentType = "filters") {
            Row(Modifier.fillMaxWidth().background(SpokifyInk).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileButton(onOpenProfile)
                HomePill("All", true) {}
                HomePill("Music", false, onOpenFavorites)
                HomePill("Downloads", false, onOpenDownloads)
            }
        }
        item(key = "shortcuts", contentType = "shortcuts") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shortcut(
                        title = "On Repeat",
                        modifier = Modifier.weight(1f),
                        isPlaying = isPlaying && activeShortcutKey == "on-repeat",
                        onClick = onOpenOnRepeat,
                        customArtwork = {
                            OnRepeatArtwork(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
                                compact = true,
                            )
                        },
                    )
                    Shortcut(
                        title = "Liked Songs",
                        modifier = Modifier.weight(1f),
                        icon = R.drawable.ic_favorite,
                        isPlaying = isPlaying && activeShortcutKey == "liked-songs",
                        onClick = onOpenFavorites,
                    )
                }
                dynamicShortcuts.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            when (item) {
                                is HomeShortcutItem.Playlist -> Shortcut(
                                    title = item.card.name,
                                    modifier = Modifier.weight(1f),
                                    artwork = item.card.artworkUrl,
                                    isPlaying = isPlaying && activeShortcutKey == "playlist:${item.card.id}",
                                    onClick = { onOpenPlaylist(item.card) },
                                )
                                is HomeShortcutItem.Song -> Shortcut(
                                    title = item.track.title,
                                    modifier = Modifier.weight(1f),
                                    artwork = item.track.artworkUrl,
                                    isPlaying = isPlaying && activeShortcutKey == "track:${item.track.uid}",
                                    onClick = { onPlay(item.track) },
                                )
                            }
                        }
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
                            Artwork(track.artworkUrl, "${track.title} artwork", Modifier.size(160.dp).clip(RoundedCornerShape(6.dp)), sizePx = 640)
                            Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
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
    val background by animateColorAsState(if (selected) SpokifyGreen else Color(0xFF282828), motionTween(160), label = "filter fill")
    Text(
        title,
        color = foreground,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .motionClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 16.dp))
}

@Composable
private fun Shortcut(
    title: String,
    modifier: Modifier,
    icon: Int? = null,
    artwork: String? = null,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    customArtwork: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF2A2A2A))
            .motionClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            customArtwork != null -> customArtwork()
            icon != null -> {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                        .background(Brush.linearGradient(if (icon == R.drawable.ic_favorite) listOf(Color(0xFF4824B8), Color(0xFFAFD9CF)) else listOf(Color(0xFF16634F), Color(0xFF213B31)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.White)
                }
            }
            else -> {
                Artwork(
                    artwork,
                    null,
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
                )
            }
        }
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (isPlaying) {
            NowPlayingEqualizerBars(
                isPlaying = true,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(width = 14.dp, height = 14.dp),
            )
        }
    }
}

