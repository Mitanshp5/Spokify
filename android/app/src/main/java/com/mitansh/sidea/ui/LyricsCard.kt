package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.core.Connectivity
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.lyrics.Lyrics
import com.mitansh.sidea.data.lyrics.LyricsOfflineException
import com.mitansh.sidea.data.lyrics.LyricsQuery
import com.mitansh.sidea.data.lyrics.LyricsRepository
import kotlinx.coroutines.CancellationException
import java.io.File

private sealed interface LyricsState {
    data object Loading : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
    data object Missing : LyricsState
    data object Offline : LyricsState
    data object Failed : LyricsState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsCard(track: Track, positionMs: Long, onSeek: (Long) -> Unit, isPlaying: Boolean, onPlayPause: () -> Unit, background: Color = Color(0xFF365A49)) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { LyricsRepository(File(context.filesDir, "lyrics")) }
    val online by remember(context) { Connectivity.observe(context) }.collectAsState(initial = Connectivity.isOnline(context))
    val queryKey = LyricsQuery.from(track).key
    var state by remember(queryKey) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    var retry by remember(queryKey) { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(queryKey, online, retry) {
        state = LyricsState.Loading
        state = try {
            repository.load(track, online)?.let { LyricsState.Ready(it) } ?: LyricsState.Missing
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: LyricsOfflineException) { LyricsState.Offline
        } catch (_: Exception) { LyricsState.Failed }
    }
    val lyrics = (state as? LyricsState.Ready)?.lyrics
    val activeIndex = lyrics?.activeIndex(positionMs) ?: -1

    Surface(shape = RoundedCornerShape(16.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClickLabel = "Open lyrics") { expanded = true }.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MotionIconButton(onClick = { expanded = true }) { Icon(androidx.compose.ui.res.painterResource(com.mitansh.sidea.R.drawable.ic_expand), "Open lyrics", tint = Color.White) }
            }
            if (lyrics != null && !lyrics.instrumental) {
                val preview = if (lyrics.isSynced) lyrics.lines.drop(activeIndex.coerceAtLeast(0)).filter { it.text.isNotBlank() }.take(3).map { it.text }
                    else lyrics.plainText.lineSequence().filter { it.isNotBlank() }.take(3).toList()
                preview.forEachIndexed { index, line ->
                    Text(line, color = if (index == 0) Color.White else Color.White.copy(alpha = .55f), fontSize = 25.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 12.dp))
                }
                if (preview.isEmpty()) Text("♪", color = Color.White, fontSize = 28.sp)
                Text(if (lyrics.isSynced) "Live lyrics · LRCLIB" else "Lyrics · LRCLIB", color = Color.White.copy(alpha = .65f), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
            } else {
                LyricsStatus(state, onRetry = { retry++ })
            }
        }
    }
    if (expanded) {
        MotionBottomSheet(onDismiss = { expanded = false }, color = background) { dismiss ->
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lyrics", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${track.title} · ${track.artist}", color = Color.White.copy(alpha = .7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = dismiss) { Text("Close lyrics", color = Color.White) }
                }
                if (lyrics != null && !lyrics.instrumental) {
                    key(queryKey) { LyricsLines(lyrics, positionMs, onSeek, Modifier.weight(1f)) }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        LyricsStatus(state, onRetry = { retry++ })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(positionMs), color = Color.White)
                    TextButton(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Play", color = Color.White, fontWeight = FontWeight.Bold) }
                    Text("LRCLIB", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LyricsLines(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val active = lyrics.activeIndex(positionMs)
    val motion = LocalMotionEnabled.current
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow, motion) {
        if (lyrics.isSynced && follow) {
            if (motion) listState.animateScrollToItem(active.coerceAtLeast(0), -80)
            else listState.scrollToItem(active.coerceAtLeast(0), -80)
        }
    }
    Column(modifier) {
        if (lyrics.isSynced) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tap a line to seek", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                TextButton(onClick = { follow = !follow }) { Text(if (follow) "Following lyrics" else "Follow lyrics", color = Color.White) }
            }
        } else {
            Text("Timing unavailable for this song", color = Color.White.copy(alpha = .7f), fontSize = 12.sp, modifier = Modifier.padding(24.dp, 12.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 160.dp)) {
            if (lyrics.isSynced) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val color by animateColorAsState(when { index == active -> Color.White; index < active -> Color.White.copy(alpha = .7f); else -> Color.White.copy(alpha = .55f) }, motionTween(220), label = "lyric highlight")
                    Text(line.text.ifBlank { "♪" }, color = color,
                        fontSize = 29.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().semantics { selected = index == active }
                            .clickable(onClickLabel = "Seek to ${formatPlaybackTime(line.timeMs)}") { onSeek(line.timeMs) }.padding(vertical = 10.dp))
                }
            } else {
                itemsIndexed(lyrics.plainText.lines()) { _, line ->
                    Text(line.ifBlank { " " }, color = Color.White, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun LyricsStatus(state: LyricsState, onRetry: () -> Unit) {
    when (state) {
        LyricsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Text("Loading lyrics…", color = Color.White, modifier = Modifier.padding(start = 12.dp))
        }
        is LyricsState.Ready -> Text("Instrumental · no lyrics", color = Color.White, modifier = Modifier.padding(vertical = 16.dp))
        else -> {
            Text(when (state) {
                LyricsState.Missing -> "No lyrics found for this recording."
                LyricsState.Offline -> "Go online to load lyrics for this song. Previously loaded lyrics are available offline."
                else -> "Couldn't load lyrics. Try again."
            }, color = Color.White, modifier = Modifier.padding(vertical = 12.dp))
            if (state != LyricsState.Offline) TextButton(onClick = onRetry) { Text("Retry lyrics", color = Color.White) }
        }
    }
}
