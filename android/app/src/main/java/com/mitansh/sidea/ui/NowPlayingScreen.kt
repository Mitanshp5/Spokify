package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.mitansh.sidea.R
import com.mitansh.sidea.core.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    title: String, artist: String, artworkUrl: String?, isPlaying: Boolean, isBuffering: Boolean,
    isFavorite: Boolean, positionMs: Long, durationMs: Long, queueSize: Int,
    queue: List<QueueEntry> = emptyList(), currentQueueIndex: Int = -1,
    shuffleEnabled: Boolean, repeatMode: Int, onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onToggleFavorite: () -> Unit, onDownload: () -> Unit,
    onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit, onShowQueue: () -> Unit,
    onSelectQueueEntry: (Int) -> Unit = {}, onClose: () -> Unit, modifier: Modifier = Modifier,
    trackId: String = "", onAddToPlaylist: (() -> Unit)? = null, error: String? = null,
    onRetry: () -> Unit = onPlayPause,
    queueTitle: String = "Your music",
) {
    var showQueue by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var scrubPosition by remember(trackId) { mutableStateOf<Float?>(null) }
    var artworkColor by remember(artworkUrl) { mutableStateOf(Color(0xFF42574F)) }
    val backgroundColor by animateColorAsState(artworkColor, motionTween(500), label = "artwork background")
    val environment = LocalSongEnvironment.current
    val track = Track(trackId, title, artist, durationMs, artworkUrl)
    val download = environment.downloads[trackId]
    val context = LocalContext.current
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(backgroundColor, lerp(backgroundColor, Color.Black, .65f), SpokifyInk))).systemBarsPadding()) {
        val coverSize = minOf(maxWidth - 48.dp, maxHeight * .48f)
        val scroll = rememberScrollState()
        val collapseAt = with(LocalDensity.current) { (coverSize + 64.dp).toPx() }
        val collapsed by remember(collapseAt) { derivedStateOf { scroll.value > 0 && scroll.value > minOf(collapseAt, scroll.maxValue * .6f) } }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp).playerDragHandle(), verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_chevron_down), "Close the player", tint = Color.White) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playing from", color = Color.White, fontSize = 11.sp)
                    Text(queueTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton(onClick = { showActions = true }) { Icon(painterResource(R.drawable.ic_more), "More song options", tint = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            PlayerArtwork(trackId, artworkUrl, "$title artwork", Modifier.size(coverSize).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(6.dp)), onColor = { artworkColor = it })
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Crossfade(title to artist, modifier = Modifier.weight(1f).padding(end = 12.dp), animationSpec = motionTween(180), label = "song details") { details ->
                    Column {
                        Text(details.first, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(details.second, color = Color.White.copy(alpha = .7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                SongSaveButton(track, isFavorite, onToggleFavorite)
            }
            Spacer(Modifier.height(10.dp))
            val maximum = durationMs.coerceAtLeast(1L).toFloat()
            PlaybackSeekBar(
                value = (scrubPosition ?: positionMs.toFloat()).coerceIn(0f, maximum),
                onChange = { scrubPosition = it },
                onFinish = { scrubPosition?.let { onSeek(it.toLong()) }; scrubPosition = null },
                maximum = maximum,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPlaybackTime(scrubPosition?.toLong() ?: positionMs), color = SpokifyMuted, fontSize = 11.sp)
                Text(formatPlaybackTime(durationMs), color = SpokifyMuted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                PlaybackModeButton(shuffleEnabled, R.drawable.ic_shuffle, if (shuffleEnabled) "Turn shuffle off" else "Turn shuffle on", onToggleShuffle)
                MotionIconButton(onClick = onPrevious) { Icon(painterResource(R.drawable.ic_previous), "Previous track", Modifier.size(30.dp), tint = Color.White) }
                MotionFilledIconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    if (isBuffering) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
                    else PlayPauseIcon(isPlaying, Modifier.size(32.dp), Color.Black)
                }
                MotionIconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_next), "Next track", Modifier.size(30.dp), tint = Color.White) }
                PlaybackModeButton(repeatMode != Player.REPEAT_MODE_OFF, R.drawable.ic_repeat,
                    when (repeatMode) { Player.REPEAT_MODE_ONE -> "Repeat one, change repeat mode"; Player.REPEAT_MODE_ALL -> "Repeat all, change repeat mode"; else -> "Repeat off, change repeat mode" },
                    onCycleRepeat, badge = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadAction(download, title) { if (download == null) onDownload() else showActions = true }
                    if (download != null) Text(downloadLabel(download), color = if (download.isComplete) SpokifyGreen else SpokifyMuted, fontSize = 11.sp)
                }
                Row {
                    MotionIconButton(onClick = {
                        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "$title — $artist\nhttps://music.youtube.com/watch?v=$trackId"), "Share song"))
                    }) { Icon(painterResource(R.drawable.ic_share), "Share song", tint = Color.White) }
                    MotionIconButton(onClick = { showQueue = true; onShowQueue() }) { Icon(painterResource(R.drawable.ic_queue), "Open queue, $queueSize songs", tint = Color.White) }
                }
            }
            error?.let {
                Text(it, color = SpokifyError, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onRetry) { Text("Retry playback", color = SpokifyGreen) }
            }
            Spacer(Modifier.height(20.dp))
            LyricsCard(track, positionMs, onSeek, isPlaying, onPlayPause, background = lerp(backgroundColor, Color(0xFF842343), .5f))
            Spacer(Modifier.height(24.dp))
        }
        AnimatedVisibility(collapsed, modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(motionTween(160)) + slideInVertically(motionTween(180)) { -it / 3 }, exit = fadeOut(motionTween(120))) {
            Column(Modifier.fillMaxWidth().background(backgroundColor)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, color = SpokifyMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SongSaveButton(track, isFavorite, onToggleFavorite)
                    MotionIconButton(onClick = onPlayPause) { PlayPauseIcon(isPlaying) }
                }
                LinearProgressIndicator(progress = { if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
                    color = Color.White, trackColor = Color.White.copy(alpha = .15f), modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
    if (showActions) SongOptionsSheet(track, isFavorite, onToggleFavorite, onDownload, onAddToPlaylist, onDismiss = { showActions = false })
    if (showQueue) {
        MotionBottomSheet(onDismiss = { showQueue = false }) { _ ->
            Text("Queue", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(queue, key = { it.index }) { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).motionClickable { onSelectQueueEntry(entry.index) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, color = if (entry.index == currentQueueIndex) SpokifyGreen else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.artist, color = SpokifyMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.index == currentQueueIndex) Icon(painterResource(R.drawable.ic_music), "Current song", tint = SpokifyGreen)
                    }
                }
            }
        }
    }
}

data class QueueEntry(val index: Int, val title: String, val artist: String)
