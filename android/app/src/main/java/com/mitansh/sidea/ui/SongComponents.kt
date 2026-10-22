package com.mitansh.sidea.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.offline.OfflineItem
import com.mitansh.sidea.data.offline.OfflineStatus

data class SongEnvironment(
    val activeUid: String? = null,
    val downloads: DownloadUiState = DownloadUiState(),
    val onRemove: (String) -> Unit = {},
    val onRetry: (Track) -> Unit = {},
    val savedUids: Set<String> = emptySet(),
    val savingUids: Set<String> = emptySet(),
    val onSave: ((Track) -> Unit)? = null,
    val onManageSave: ((Track) -> Unit)? = null,
)

val LocalSongEnvironment = compositionLocalOf { SongEnvironment() }

@Composable
fun SongSaveButton(track: Track, isFavorite: Boolean = false, onSave: () -> Unit = {}) {
    val environment = LocalSongEnvironment.current
    val saved = isFavorite || track.uid in environment.savedUids
    MotionIconButton(onClick = { environment.onSave?.invoke(track) ?: onSave() },
        enabled = track.uid !in environment.savingUids,
        modifier = Modifier.semantics { stateDescription = if (saved) "Saved" else "Not saved" }) {
        SaveIcon(saved, Modifier.size(24.dp), if (saved) "Edit saved playlists for ${track.title}" else "Add ${track.title} to Liked Songs")
    }
}

fun downloadLabel(item: OfflineItem?): String = when (item?.status) {
    null -> "Download"
    OfflineStatus.COMPLETED -> "Downloaded"
    OfflineStatus.QUEUED -> "Queued"
    OfflineStatus.WAITING -> "Waiting for connection"
    OfflineStatus.DOWNLOADING -> if (item.percent >= 0) "Downloading ${(item.percent * 100).toInt()}%" else "Downloading"
    OfflineStatus.STOPPED -> "Paused"
    OfflineStatus.FAILED -> "Download failed · retry"
    OfflineStatus.REMOVING -> "Removing download"
}

@Composable
fun DownloadAction(item: OfflineItem?, title: String, onClick: () -> Unit) {
    val label = downloadLabel(item)
    val progress by animateFloatAsState((item?.percent ?: 0f).coerceIn(0f, 1f), motionTween(200), label = "download progress")
    var previous by remember { mutableStateOf(item?.status) }
    val completion = remember { androidx.compose.animation.core.Animatable(1f) }
    val motion = LocalMotionEnabled.current
    LaunchedEffect(item?.status, motion) {
        if (previous != OfflineStatus.COMPLETED && item?.status == OfflineStatus.COMPLETED && motion) {
            completion.snapTo(1.2f)
            completion.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .7f, stiffness = 650f))
        }
        previous = item?.status
    }
    MotionIconButton(onClick = onClick, modifier = Modifier.semantics { stateDescription = label }) {
        Crossfade(item?.status, animationSpec = motionTween(140), label = "download state") { status ->
        if (status in setOf(OfflineStatus.DOWNLOADING, OfflineStatus.QUEUED, OfflineStatus.WAITING, OfflineStatus.REMOVING)) {
            Box(Modifier.size(24.dp).semantics { contentDescription = "$label $title" }, contentAlignment = Alignment.Center) {
                if (item?.status == OfflineStatus.DOWNLOADING && item.percent >= 0) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), color = SpokifyGreen, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(Modifier.size(22.dp), color = SpokifyGreen, strokeWidth = 2.dp)
                }
            }
        } else {
            Icon(
                painterResource(when (status) {
                    OfflineStatus.COMPLETED -> R.drawable.ic_downloaded
                    OfflineStatus.FAILED -> R.drawable.ic_retry
                    else -> R.drawable.ic_download
                }),
                "$label $title",
                Modifier.size(22.dp).graphicsLayer { scaleX = completion.value; scaleY = completion.value },
                tint = if (status == OfflineStatus.COMPLETED) SpokifyGreen else SpokifyMuted,
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    track: Track,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onDownload: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    extraActions: @Composable ColumnScope.() -> Unit = {},
) {
    val environment = LocalSongEnvironment.current
    val item = environment.downloads[track.id]
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    MotionBottomSheet(onDismiss = { onDismiss(); afterDismiss?.invoke() }, color = Color(0xFF242424)) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = SpokifyMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item?.let { Text(downloadLabel(it), color = SpokifyGreen, fontSize = 12.sp) }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .08f))
        val manage = environment.onManageSave?.let { action -> { action(track) } } ?: onAddToPlaylist
        SheetAction("Add to playlist", R.drawable.ic_add_circle) {
            if (manage != null) afterDismiss = manage else onFavorite()
            dismiss()
        }
        if (onDownload != null) {
            val label = when (item?.status) {
                OfflineStatus.COMPLETED -> "Remove download"
                OfflineStatus.QUEUED, OfflineStatus.DOWNLOADING, OfflineStatus.WAITING, OfflineStatus.STOPPED -> "Cancel download"
                OfflineStatus.FAILED -> "Retry download"
                else -> "Download"
            }
            SheetAction(label, if (item?.isComplete == true) R.drawable.ic_downloaded else R.drawable.ic_download) {
                when (item?.status) {
                    OfflineStatus.COMPLETED, OfflineStatus.QUEUED, OfflineStatus.DOWNLOADING, OfflineStatus.WAITING, OfflineStatus.STOPPED -> environment.onRemove(track.id)
                    OfflineStatus.FAILED -> environment.onRetry(track)
                    else -> onDownload()
                }
                dismiss()
            }
        }
        onHide?.let { SheetAction("Don't recommend this song", R.drawable.ic_close) { it(); dismiss() } }
        extraActions()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SheetAction(label: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).motionClickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = SpokifyMuted)
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
fun SongRow(
    track: Track,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    extraActions: @Composable ColumnScope.() -> Unit = {},
    showUnsavedSave: Boolean = false,
) {
    val environment = LocalSongEnvironment.current
    val download = environment.downloads[track.id]
    var showActions by remember(track.uid) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp).motionClickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUrl, "${track.title} artwork", Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(track.title, color = if (environment.activeUid == track.uid) SpokifyGreen else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.animateContentSize(motionTween(180)), verticalAlignment = Alignment.CenterVertically) {
                if (download?.isComplete == true) {
                    Icon(painterResource(R.drawable.ic_downloaded), "Available offline", Modifier.size(12.dp), tint = SpokifyGreen)
                    Spacer(Modifier.width(5.dp))
                }
                val subtitle = if (download != null && !download.isComplete) "${downloadLabel(download)} · ${track.artist}" else track.artist
                Text(subtitle, color = SpokifyMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Spotify keeps uncollected rows clean; save/download actions remain in the song menu.
        if (showUnsavedSave || track.uid in environment.savedUids || isFavorite) SongSaveButton(track, isFavorite, onFavorite)
        MotionIconButton(onClick = { showActions = true }) {
            Icon(painterResource(R.drawable.ic_more), "More options for ${track.title}", tint = SpokifyMuted)
        }
    }
    if (showActions) SongOptionsSheet(track, isFavorite, onFavorite, onDownload, onAddToPlaylist, onHide, onDismiss = { showActions = false }, extraActions = extraActions)
}

@Composable
fun SongList(
    tracks: List<Track>, favorites: List<Track>, onPlay: (Track) -> Unit,
    onFavorite: (Track) -> Unit,
    onDownload: ((Track) -> Unit)? = null, onAddToPlaylist: ((Track) -> Unit)? = null,
    onHide: ((Track) -> Unit)? = null, modifier: Modifier = Modifier, emptyMessage: String? = null,
) {
    if (tracks.isEmpty()) {
        emptyMessage?.let { Text(it, color = SpokifyMuted, modifier = Modifier.padding(24.dp)) }
        return
    }
    val favoriteUids = remember(favorites) { favorites.mapTo(hashSetOf()) { it.uid } }
    // Keep repeated recordings legal while retaining identity when other songs move.
    val rowKeys = remember(tracks) { songRowKeys(tracks) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp + LocalBottomContentPadding.current)) {
        itemsIndexed(tracks, key = { index, _ -> rowKeys[index] }, contentType = { _, _ -> "song" }) { _, track ->
            SongRow(track, track.uid in favoriteUids, { onPlay(track) }, { onFavorite(track) },
                onDownload = onDownload?.let { action -> { action(track) } },
                onAddToPlaylist = onAddToPlaylist?.let { action -> { action(track) } },
                onHide = onHide?.let { action -> { action(track) } }, modifier = Modifier.animateItem(), showUnsavedSave = true)
        }
    }
}

@Composable
fun MiniPlayer(title: String, artist: String, artworkUrl: String?, isPlaying: Boolean, progress: Float, onPlayPause: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier, trackId: String = "", song: Track? = null) {
    var artworkColor by remember(artworkUrl) { mutableStateOf(Color(0xFF263A33)) }
    val background by animateColorAsState(artworkColor, motionTween(500), label = "mini artwork colour")
    Column(modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().height(62.dp).clip(RoundedCornerShape(8.dp)).background(background).motionClickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerArtwork(trackId, artworkUrl, null, Modifier.size(42.dp).clip(RoundedCornerShape(4.dp)), onColor = { artworkColor = it }, sizePx = 256)
            Crossfade(title to artist, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), animationSpec = motionTween(180), label = "mini song") { details ->
                Column {
                    Text(details.first, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = if (LocalMotionEnabled.current) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (LocalMotionEnabled.current) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1600) else Modifier)
                    Text(details.second, color = Color(0xFFD5DFD9), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SongSaveButton(song ?: Track(trackId, title, artist, artworkUrl = artworkUrl))
            MotionIconButton(onClick = onPlayPause) { PlayPauseIcon(isPlaying, Modifier.size(28.dp)) }
        }
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp), color = Color.White, trackColor = Color.White.copy(alpha = .2f))
    }
}
