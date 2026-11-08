package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.mitansh.sidea.R
import com.mitansh.sidea.playback.QueueOrigin
import kotlinx.coroutines.launch

/** Owned by NowPlaying, outside the queue/timer conditional so returning does not reset it. */
class QueueSheetState(val scroll: LazyListState) {
    val rowKeys = QueueRowKeys()
    var contentTop by mutableFloatStateOf(0f)
    var editing by mutableStateOf(false)
    var selected by mutableStateOf(setOf<Any>())
    var currentIdentity: Any? = null
}

@Composable
fun rememberQueueSheetState(): QueueSheetState {
    val scroll = rememberLazyListState()
    return remember(scroll) { QueueSheetState(scroll) }
}

@Composable
fun NowPlayingEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = SpokifyGreen
) {
    if (!isPlaying) return
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val h1 by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 0.45f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "h3"
    )
    Row(
        modifier = modifier.size(width = 13.dp, height = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(h1, h2, h3).forEach { heightFraction ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun QueueModeTile(
    selected: Boolean,
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    val tint by animateColorAsState(if (selected) SpokifyGreen else Color.White, motionTween(160), label = "tile tint")
    Box(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF282828))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                badge?.let {
                    Text(
                        it,
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { hideFromAccessibility() }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    snapshot: QueueSnapshot, onDismiss: () -> Unit, onAction: (QueueAction) -> Unit,
    context: String = "Your music", isPlaying: Boolean = false, onPlayPause: () -> Unit = {},
    repeatMode: Int = Player.REPEAT_MODE_OFF, onShuffle: () -> Unit = {}, onRepeat: () -> Unit = {},
    timerActive: Boolean = false, onTimer: () -> Unit = {},
    message: String? = null,
    state: QueueSheetState = rememberQueueSheetState(),
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
) {
    val presentation = snapshot.presentation
    val scroll = state.scroll
    val rowKeys = state.rowKeys
    var editing by state::editing
    var selected by state::selected
    val validSelection = queueSelection(selected, snapshot)
    LaunchedEffect(snapshot) { selected = validSelection }
    LaunchedEffect(presentation.current?.instanceId) {
        val identity = presentation.current?.instanceId
        if (state.currentIdentity != identity) {
            state.currentIdentity = identity
            scroll.scrollToItem(0)
        }
    }
    val scope = rememberCoroutineScope()
    MotionBottomSheet(onDismiss = onDismiss, sheetState = sheetState) { _ ->
        var contentTop by state::contentTop
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.88f)
            .onGloballyPositioned { contentTop = it.positionInWindow().y }) {
        val density = LocalDensity.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val heightPx = with(density) { maxHeight.toPx() }
        val visibleBottom = windowHeight - WindowInsets.navigationBars.getBottom(density)
        val obscured = (contentTop + heightPx - visibleBottom).coerceIn(0f, heightPx)
        Column(Modifier.fillMaxSize().padding(bottom = with(density) { obscured.toDp() })) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (editing) "Edit queue" else "Queue", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (!editing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Playing ", color = SpokifyMuted, fontSize = 13.sp)
                            Text(
                                presentation.current?.title ?: context,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (editing) {
                    IconButton(onClick = { editing = false; selected = emptySet() }, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_close), "Close edit queue", tint = Color.White)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (presentation.upcoming.any { it.origin == QueueOrigin.MANUAL }) {
                            TextButton(
                                onClick = { onAction(QueueAction.ClearManual) },
                                modifier = Modifier.heightIn(min = 36.dp).semantics { contentDescription = "Clear manual queue" }
                            ) { Text("Clear", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF282828))
                                .clickable { editing = true }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Edit", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            presentation.current?.let { current ->
                QueueSong(current, true, isPlaying = isPlaying, onClick = { onAction(QueueAction.Select(current)) }, trailing = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(onClick = onPlayPause)
                            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                })
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("queue-list"), state = scroll,
                contentPadding = PaddingValues(vertical = 4.dp)) {
                if (presentation.current == null) item { QueueSection("Your queue is empty") }
                else if (presentation.upcoming.isEmpty()) item { QueueSection("You're at the end of the queue") }
                itemsIndexed(presentation.upcoming, key = { _, entry -> rowKeys.key(entry.instanceId) }) { position, entry ->
                    if (position == 0 || presentation.upcoming[position - 1].origin != entry.origin) {
                        QueueSection(when (entry.origin) {
                            QueueOrigin.MANUAL -> "Next in queue"
                            QueueOrigin.CONTEXT -> "Next from $context"
                            QueueOrigin.RADIO -> "Next up: Recommended tracks"
                        })
                    }
                    fun move(up: Boolean) { onAction(QueueAction.Move(entry, up)) }
                    val canUp = position > 0 && presentation.upcoming[position - 1].origin == entry.origin
                    val canDown = position < presentation.upcoming.lastIndex && presentation.upcoming[position + 1].origin == entry.origin
                    var drag by remember(entry.instanceId) { mutableFloatStateOf(0f) }
                    var rowHeight by remember(entry.instanceId) { mutableFloatStateOf(0f) }
                    LaunchedEffect(snapshot) { drag = 0f }
                    QueueSong(entry, false, isPlaying = false, onClick = {
                        if (editing) selected = if (entry.instanceId in validSelection) validSelection - entry.instanceId else validSelection + entry.instanceId
                        else onAction(QueueAction.Select(entry))
                    }, leading = {
                        if (editing) Checkbox(
                            checked = entry.instanceId in validSelection,
                            onCheckedChange = { checked ->
                                selected = if (checked) validSelection + entry.instanceId else validSelection - entry.instanceId
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = SpokifyGreen,
                                uncheckedColor = Color(0xFF777777),
                                checkmarkColor = Color.Black
                            ),
                            modifier = Modifier.size(44.dp).semantics { contentDescription = "Select ${entry.title}, queue position ${entry.index + 1}" }
                        )
                    }, trailing = {
                        val density = LocalDensity.current
                        val threshold = rowHeight.takeIf { it > 0f } ?: with(density) { 72.dp.toPx() }
                        Box(Modifier.size(48.dp).semantics {
                            contentDescription = "Reorder ${entry.title}, queue position ${entry.index + 1}"
                            customActions = buildList {
                                if (canUp) add(CustomAccessibilityAction("Move up") { move(true); true })
                                if (canDown) add(CustomAccessibilityAction("Move down") { move(false); true })
                            }
                        }.pointerInput(snapshot, entry.instanceId) {
                            detectDragGesturesAfterLongPress(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                                onDragEnd = {
                                    val groupStart = (0..position).lastOrNull { it == 0 || presentation.upcoming[it - 1].origin != entry.origin } ?: position
                                    val groupEnd = (position..presentation.upcoming.lastIndex).firstOrNull { it == presentation.upcoming.lastIndex || presentation.upcoming[it + 1].origin != entry.origin } ?: position
                                    val targetPosition = (position + (drag / threshold).toInt()).coerceIn(groupStart, groupEnd)
                                    val target = presentation.upcoming[targetPosition]
                                    if (target != entry && target.origin == entry.origin) onAction(QueueAction.MoveTo(entry, target))
                                    drag = 0f
                                }) { change, amount ->
                                change.consume(); drag += amount.y
                                val info = scroll.layoutInfo
                                val row = info.visibleItemsInfo.firstOrNull { it.key == rowKeys.key(entry.instanceId) }
                                if (row != null && (row.offset + drag + row.size > info.viewportEndOffset - threshold || row.offset + drag < info.viewportStartOffset + threshold))
                                    scope.launch { scroll.scroll { drag += scrollBy(amount.y) } }
                            }
                        }, contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_drag_handle),
                                contentDescription = null,
                                tint = SpokifyMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }, modifier = Modifier.zIndex(if (drag != 0f) 1f else 0f).graphicsLayer { translationY = drag }
                        .onSizeChanged { rowHeight = it.height.toFloat() }.semantics {
                        customActions = buildList {
                            if (canUp) add(CustomAccessibilityAction("Move up") { move(true); true })
                            if (canDown) add(CustomAccessibilityAction("Move down") { move(false); true })
                        }
                    })
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            message?.let { Text(it, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }) }
            if (editing) {
                val entries = presentation.upcoming.filter { it.instanceId in validSelection }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val canMoveUp = entries.any { entry ->
                        val index = presentation.upcoming.indexOf(entry)
                        index > 0 && presentation.upcoming[index - 1].origin == entry.origin && presentation.upcoming[index - 1].instanceId !in validSelection
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (canMoveUp) Color(0xFF282828) else Color(0xFF1E1E1E))
                            .clickable(enabled = canMoveUp) {
                                entries.forEach { entry ->
                                    val index = presentation.upcoming.indexOf(entry)
                                    val target = presentation.upcoming.take(index).lastOrNull { it.instanceId !in validSelection }
                                    if (target != null && target.origin == entry.origin) onAction(QueueAction.MoveTo(entry, target))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Move up", color = if (canMoveUp) Color.White else Color(0xFF555555), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    val canRemove = entries.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (canRemove) Color(0xFF282828) else Color(0xFF1E1E1E))
                            .clickable(enabled = canRemove) {
                                onAction(QueueAction.RemoveSelected(entries))
                                selected = emptySet()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Remove", color = if (canRemove) Color.White else Color(0xFF555555), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QueueModeTile(
                        selected = snapshot.shuffled,
                        icon = R.drawable.ic_shuffle,
                        label = "Shuffle",
                        onClick = onShuffle,
                        modifier = Modifier.weight(1f)
                    )
                    QueueModeTile(
                        selected = repeatMode != Player.REPEAT_MODE_OFF,
                        icon = R.drawable.ic_repeat,
                        label = "Repeat",
                        onClick = onRepeat,
                        modifier = Modifier.weight(1f),
                        badge = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null
                    )
                    QueueModeTile(
                        selected = timerActive,
                        icon = R.drawable.ic_timer,
                        label = "Timer",
                        onClick = onTimer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        }
    }
}

@Composable
private fun QueueSection(label: String) {
    Text(label, color = SpokifyMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun QueueSong(
    entry: QueueEntry,
    current: Boolean,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit
) {
    Row(modifier.fillMaxWidth().testTag("queue-row-${entry.index}").motionClickable(onClick = onClick)
        .semantics { contentDescription = "${if (current) "Resume" else "Play"} ${entry.title}, queue position ${entry.index + 1}" }
        .heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading()
        Artwork(entry.artworkUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current && isPlaying) {
                    NowPlayingEqualizerBars(isPlaying = true, modifier = Modifier.padding(end = 6.dp))
                }
                Text(
                    entry.title,
                    color = if (current) SpokifyGreen else Color.White,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(entry.artist, color = SpokifyMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
