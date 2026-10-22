package com.mitansh.sidea.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track

@Composable
fun SearchScreen(
    query: String, onQueryChange: (String) -> Unit, loading: Boolean, error: String?,
    results: List<Track>, favorites: List<Track>, onSearch: (String) -> Unit,
    onPlay: (Track) -> Unit, onFavorite: (Track) -> Unit, onDownload: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit, onHide: (Track) -> Unit = {}, modifier: Modifier = Modifier,
    recent: List<RecentSearch> = emptyList(), onRemoveRecent: (String) -> Unit = {}, onClearHistory: () -> Unit = {},
    onOpenProfile: () -> Unit = {}, onOpenOnRepeat: () -> Unit = {}, browseTracks: List<Track> = emptyList(),
) {
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    var focused by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    BackHandler(focused) { focused = false; focus.clearFocus(); onQueryChange("") }
    LaunchedEffect(focused) { if (focused) requester.requestFocus() }
    Column(modifier.fillMaxSize().background(SpokifyInk)) {
        if (!focused) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileButton(onOpenProfile)
                Text("Search", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            }
            Surface(color = Color.White, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp)
                .motionClickable { focused = true }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_search), null, tint = Color.Black)
                    Text("What do you want to listen to?", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp + LocalBottomContentPadding.current), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("Browse all", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                val categories = listOf("Music" to Color(0xFFE31689), "Made for you" to Color(0xFF36519D), "Chill" to Color(0xFF167568), "Focus" to Color(0xFF8053A3), "Indie" to Color(0xFFB44727), "Feel good" to Color(0xFFB2750C))
                items(categories.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { (label, color) ->
                            Box(Modifier.weight(1f).height(88.dp).clip(RoundedCornerShape(7.dp)).background(color)
                                .motionClickable {
                                    if (label == "Made for you") onOpenOnRepeat()
                                    else { focused = true; onSearch(if (label == "Music") "new music" else "$label music") }
                                }) {
                                Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(.72f))
                                val cover = browseTracks.getOrNull(categories.indexOfFirst { it.first == label })?.artworkUrl
                                if (cover != null) Artwork(cover, null, Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 14.dp).size(74.dp).rotate(23f).clip(RoundedCornerShape(5.dp)))
                                else Icon(painterResource(if (label == "Made for you") R.drawable.ic_repeat else R.drawable.ic_music), null,
                                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 10.dp).size(66.dp).rotate(23f), tint = Color.White.copy(alpha = .55f))
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().background(Color(0xFF202020)).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(onClick = { focused = false; focus.clearFocus(); onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_back), "Back to browse", tint = Color.White)
                }
                TextField(value = query, onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(requester),
                    placeholder = { Text("What do you want to listen to?", fontSize = 14.sp) }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) MotionIconButton(onClick = { onQueryChange("") }) { Icon(painterResource(R.drawable.ic_close), "Clear search") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query); focus.clearFocus() }),
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(focusedContainerColor = SpokifyElevated, unfocusedContainerColor = SpokifyElevated,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLeadingIconColor = Color.White,
                        unfocusedLeadingIconColor = Color.White, focusedTrailingIconColor = Color.White, unfocusedTrailingIconColor = Color.White,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White))
            }
            if (query.isBlank()) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomContentPadding.current)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (recent.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Clear all", color = SpokifyMuted) }
                        }
                    }
                    if (recent.isEmpty()) item { Text("The music you search for will appear here.", color = SpokifyMuted, modifier = Modifier.padding(16.dp)) }
                    items(recent, key = { it.key }, contentType = { if (it.track == null) "query" else "recent-song" }) { entry ->
                        Row(Modifier.fillMaxWidth().motionClickable {
                            entry.track?.let(onPlay) ?: entry.query?.let(onSearch)
                            focus.clearFocus()
                        }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (entry.track != null) Artwork(entry.track.artworkUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
                            else Box(Modifier.size(52.dp).clip(CircleShape).background(SpokifyElevated), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_search), null, tint = Color.White) }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(entry.track?.title ?: entry.query.orEmpty(), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.track?.let { "Song · ${it.artist}" } ?: "Search", color = SpokifyMuted, fontSize = 13.sp)
                            }
                            entry.track?.let { SongSaveButton(it, it.uid in favoriteUids, { onFavorite(it) }) }
                            MotionIconButton(onClick = { onRemoveRecent(entry.key) }) { Icon(painterResource(R.drawable.ic_close), "Remove ${entry.track?.title ?: entry.query} from recent searches", tint = SpokifyMuted) }
                        }
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpokifyGreen, modifier = Modifier.size(28.dp)) }
            } else if (error != null) {
                Column(Modifier.padding(24.dp)) {
                    Text("Couldn't load results", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Check your connection and try again.", color = SpokifyMuted, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { onSearch(query) }) { Text("Try again", color = SpokifyGreen) }
                }
            } else {
                Text("Songs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                SongList(results, favorites, onPlay, onFavorite, onDownload = onDownload, onAddToPlaylist = onAddToPlaylist, onHide = onHide,
                    modifier = Modifier.weight(1f), emptyMessage = "No songs found. Try another search.")
            }
        }
    }
}
