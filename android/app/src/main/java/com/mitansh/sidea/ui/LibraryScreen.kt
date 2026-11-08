package com.mitansh.sidea.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R

data class PlaylistCard(val id: String, val name: String, val songCount: Int, val artworkUrl: String?, val createdAtEpochMs: Long = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    playlists: List<PlaylistCard>, favoriteCount: Int, historyCount: Int, downloadCount: Int,
    onOpenPlaylist: (PlaylistCard) -> Unit, onOpenFavorites: () -> Unit, onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit, onOpenOfflineReady: () -> Unit,
    onOpenImport: () -> Unit, onOpenProfile: () -> Unit, onOpenSettings: () -> Unit,
    onCreatePlaylist: () -> Unit, modifier: Modifier = Modifier,
    onRepeatCount: Int = 0, onOpenOnRepeat: () -> Unit = {},
    playerVisible: Boolean = false,
    activeGroupName: String? = null,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sortOption by rememberSaveable { mutableStateOf(LibrarySortOption.RECENTS) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var playlistOnly by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        query = ""
        searching = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    // Let the foreground player handle Back without clearing the search underneath it.
    BackHandler(enabled = librarySearchBackEnabled(searching, query, playerVisible), onBack = closeSearch)
    LaunchedEffect(searching) {
        if (searching) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val presentation = remember(playlists, query, sortOption, playlistOnly) {
        LibrarySearchPresentation.from(playlists, query, sortOption, playlistOnly)
    }
    val visible = presentation.playlists
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileButton(onOpenProfile)
            Text("Your Library", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
            IconButton(onClick = { if (searching) closeSearch() else searching = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search), if (searching) "Close library search" else "Search your library", tint = Color.White)
            }
            MotionIconButton(onClick = onCreatePlaylist) { Icon(painterResource(R.drawable.ic_add), "Create playlist", tint = Color.White) }
        }
        if (searching) OutlinedTextField(
            query, { query = it }, label = { Text("Search your library") }, singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), "Clear search")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focusRequester),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomePill("Playlists", playlistOnly) { playlistOnly = !playlistOnly }
            HomePill("Downloaded", false, onOpenOfflineReady)
            HomePill("Import", false, onOpenImport)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { showSortSheet = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sort),
                    contentDescription = "Sort by ${sortOption.displayName}",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    sortOption.displayName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            MotionIconButton(onClick = { grid = !grid }) { Icon(painterResource(if (grid) R.drawable.ic_library else R.drawable.ic_grid), if (grid) "Show list" else "Show grid", tint = Color.White) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp + LocalBottomContentPadding.current)) {
            if (presentation.emptyKind == LibrarySearchEmptyKind.NO_RESULTS) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No results for “${query.trim()}”", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Try another name or clear your search", color = SpokifyMuted, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { query = "" }, modifier = Modifier.padding(top = 12.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Clear search") }
                }
            }
            if (presentation.showLikedSongs) item {
                LibraryRow(
                    "Liked Songs",
                    "📌 Playlist · $favoriteCount songs",
                    R.drawable.ic_favorite,
                    listOf(Color(0xFF4A27BA), Color(0xFFB4DCC9)),
                    onOpenFavorites,
                    isActive = activeGroupName == "Liked Songs",
                )
            }
            if (presentation.showOnRepeat) item {
                Row(Modifier.fillMaxWidth().motionClickable(onClick = onOpenOnRepeat).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    OnRepeatArtwork(Modifier.size(64.dp), compact = true)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("On Repeat", color = if (activeGroupName == "On Repeat") SpokifyGreen else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Playlist · Made for you · $onRepeatCount songs", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (presentation.showOfflineBackup) item {
                LibraryRow(
                    "Offline Backup",
                    "Playlist · $downloadCount songs ready to play",
                    R.drawable.ic_downloaded,
                    listOf(Color(0xFF176A53), Color(0xFF203D32)),
                    onOpenOfflineReady,
                    isActive = activeGroupName == "Offline Backup",
                )
            }
            if (presentation.showRecentlyPlayed) item {
                LibraryRow(
                    "Recently played",
                    "Playlist · $historyCount songs",
                    R.drawable.ic_music,
                    listOf(Color(0xFF775D30), Color(0xFF272421)),
                    onOpenHistory,
                    isActive = activeGroupName == "Recently played" || activeGroupName == "Recently Played",
                )
            }
            if (grid) items(visible.chunked(2), key = { "grid:${it.first().id}" }, contentType = { "playlist-pair" }) { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { playlist ->
                        Column(Modifier.weight(1f).motionClickable { onOpenPlaylist(playlist) }) {
                            Artwork(playlist.artworkUrl, "${playlist.name} artwork", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(5.dp)), sizePx = 640)
                            Text(playlist.name, color = if (activeGroupName == playlist.name) SpokifyGreen else Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Playlist · ${playlist.songCount} songs", color = SpokifyMuted, fontSize = 12.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            } else items(visible, key = { it.id }, contentType = { "playlist" }) { playlist ->
                Row(Modifier.animateItem().fillMaxWidth().motionClickable { onOpenPlaylist(playlist) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(playlist.artworkUrl, "${playlist.name} artwork", Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(playlist.name, color = if (activeGroupName == playlist.name) SpokifyGreen else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Playlist · ${playlist.songCount} songs", color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (presentation.emptyKind == LibrarySearchEmptyKind.NONE) item {
                TextButton(onClick = onCreatePlaylist, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) { Icon(painterResource(R.drawable.ic_add), null); Spacer(Modifier.width(12.dp)); Text("Create a playlist") }
            }
        }
    }

    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            containerColor = Color(0xFF282828),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Sort by",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                LibrarySortOption.values().forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                sortOption = option
                                showSortSheet = false
                            }
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.displayName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == sortOption) {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = "Selected",
                                tint = SpokifyGreen,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    icon: Int,
    colors: List<Color>,
    onClick: () -> Unit,
    isActive: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(28.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = if (isActive) SpokifyGreen else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
