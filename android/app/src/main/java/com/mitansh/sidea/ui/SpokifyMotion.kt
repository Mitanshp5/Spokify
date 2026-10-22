package com.mitansh.sidea.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.mitansh.sidea.R

// App timings are implementation choices, not claimed measurements of Spotify's internals.
internal val LocalMotionEnabled = compositionLocalOf { true }
private val LocalSharedPlayer = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalPlayerVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }
private val LocalPlayerExpanded = compositionLocalOf { false }
private val LocalPlayerDrag = compositionLocalOf<PlayerDrag?> { null }

@Composable
fun MotionPreference(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    fun enabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motion by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = enabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalMotionEnabled provides motion, content = content)
}

@Composable
internal fun <T> motionTween(durationMs: Int = 180): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) tween(durationMs, easing = FastOutSlowInEasing) else snap()

/** Keep the interaction source shared with clickable, so scrolling cancels press feedback. */
fun Modifier.motionClickable(onClickLabel: String? = null, scale: Float = .98f, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val amount by animateFloatAsState(if (pressed) scale else 1f, motionTween(100), label = "press")
    graphicsLayer { scaleX = amount; scaleY = amount }
        .clickable(interactionSource = source, indication = LocalIndication.current, onClickLabel = onClickLabel, enabled = enabled, onClick = onClick)
}

@Composable
fun MotionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val amount by animateFloatAsState(if (pressed) .9f else 1f, motionTween(110), label = "icon press")
    IconButton(onClick, modifier.graphicsLayer { scaleX = amount; scaleY = amount }, enabled = enabled, interactionSource = source, content = content)
}

@Composable
fun MotionFilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(), content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val amount by animateFloatAsState(if (pressed) .93f else 1f, motionTween(110), label = "play press")
    FilledIconButton(onClick, modifier.graphicsLayer { scaleX = amount; scaleY = amount }, enabled = enabled, colors = colors, interactionSource = source, content = content)
}

/** Two compatible polygons interpolate from a play triangle into pause bars. */
@Composable
fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    val phase by animateFloatAsState(if (playing) 1f else 0f, motionTween(180), label = "play pause")
    Canvas(modifier.size(28.dp).semantics { contentDescription = if (playing) "Pause" else "Play" }) {
        val play = listOf(listOf(6f to 4f, 12f to 7.43f, 12f to 16.57f, 6f to 20f),
            listOf(12f to 7.43f, 20f to 12f, 20f to 12f, 12f to 16.57f))
        val pause = listOf(listOf(6f to 4f, 10f to 4f, 10f to 20f, 6f to 20f),
            listOf(14f to 4f, 18f to 4f, 18f to 20f, 14f to 20f))
        repeat(2) { polygon ->
            val path = Path()
            repeat(4) { index ->
                val a = play[polygon][index]; val b = pause[polygon][index]
                val x = (a.first + (b.first - a.first) * phase) * size.width / 24
                val y = (a.second + (b.second - a.second) * phase) * size.height / 24
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
        }
    }
}

@Composable
fun SaveIcon(saved: Boolean, modifier: Modifier = Modifier, description: String = if (saved) "Edit saved playlists" else "Add to Liked Songs") {
    var previous by remember { mutableStateOf(saved) }
    val size = remember { Animatable(1f) }
    val motion = LocalMotionEnabled.current
    LaunchedEffect(saved, motion) {
        if (saved != previous && saved && motion) {
            size.snapTo(1.2f)
            size.animateTo(1f, spring(dampingRatio = .65f, stiffness = 600f))
        } else size.snapTo(1f)
        previous = saved
    }
    Crossfade(saved, animationSpec = motionTween(130), modifier = modifier.graphicsLayer { scaleX = size.value; scaleY = size.value }, label = "saved") { value ->
        Icon(painterResource(if (value) R.drawable.ic_saved else R.drawable.ic_add_circle),
            description, tint = if (value) SpokifyGreen else Color.White)
    }
}

data class MotionDestination(val tab: SpokifyTab, val section: String?, val playlistId: String? = null) {
    val key: String get() = if (section == "playlist") "playlist:$playlistId" else section ?: tab.name
}

@Composable
fun MotionScreens(destination: MotionDestination, modifier: Modifier = Modifier, content: @Composable (MotionDestination) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val motion = LocalMotionEnabled.current
    AnimatedContent(destination, modifier, transitionSpec = {
        val duration = if (motion) 240 else 0
        val enteringDetail = targetState.section != null && targetState.key != initialState.key
        val leavingDetail = initialState.section != null && targetState.section == null
        when {
            enteringDetail -> (fadeIn(tween(duration)) + slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { it / 5 }) togetherWith fadeOut(tween(duration / 2))
            leavingDetail -> fadeIn(tween(duration)) togetherWith (fadeOut(tween(duration / 2)) + slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { it / 5 })
            else -> fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2))
        }.using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> snap() }))
    }, contentKey = { it.key }, label = "destination") { screen ->
        holder.SaveableStateProvider(screen.key) { Box(Modifier.fillMaxSize().background(SpokifyInk)) { content(screen) } }
    }
}

internal object PlayerDismissPolicy {
    fun shouldDismiss(distancePx: Float, heightPx: Float, density: Float, velocityPx: Float): Boolean =
        distancePx > minOf(heightPx * .18f, 120f * density) || (distancePx > 24f * density && velocityPx > 1000f * density)
}

private class PlayerDrag(val move: (Float) -> Unit, val release: (Float) -> Unit, val cancel: () -> Unit)

fun Modifier.playerDragHandle(): Modifier = composed {
    val drag = LocalPlayerDrag.current
    if (drag == null) this else pointerInput(drag) {
        detectVerticalDragGestures(onVerticalDrag = { change, delta -> change.consume(); drag.move(delta) },
            onDragEnd = { drag.release(0f) }, onDragCancel = drag.cancel)
    }
}

@Composable
fun MotionAppFrame(expanded: Boolean, onDismissPlayer: () -> Unit, player: @Composable () -> Unit,
    chrome: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val dismiss by rememberUpdatedState(onDismissPlayer)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val motion = LocalMotionEnabled.current
    var drag by remember { mutableFloatStateOf(0f) }
    var reset by remember { mutableStateOf<Job?>(null) }
    var height by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(expanded) { if (expanded) { reset?.cancel(); drag = 0f } }
    val controller = remember(density, motion) {
        val returnToPlayer: () -> Unit = {
            reset?.cancel()
            reset = scope.launch {
                if (motion) animate(drag, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ -> drag = value }
                else drag = 0f
            }
        }
        PlayerDrag(move = { delta -> reset?.cancel(); drag = (drag + delta).coerceIn(0f, height) }, release = { velocity ->
            if (PlayerDismissPolicy.shouldDismiss(drag, height, density, velocity)) dismiss()
            else returnToPlayer()
        }, cancel = returnToPlayer)
    }
    val nested = remember(controller) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && drag > 0 && available.y < 0) {
                    val consumed = maxOf(available.y, -drag)
                    controller.move(consumed)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) { controller.move(available.y); return Offset(0f, available.y) }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0) return Velocity.Zero
                controller.release(available.y)
                return available
            }
        }
    }
    // Only the two player surfaces need shared-element lookahead. Keeping scrolling
    // destinations outside it avoids an extra measurement pass for every list row.
    Box(Modifier.fillMaxSize().onSizeChanged { height = it.height.toFloat() }) {
        Box(Modifier.fillMaxSize().semantics { if (expanded) hideFromAccessibility() }) { content() }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedPlayer provides this, LocalPlayerExpanded provides expanded) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().semantics { if (expanded) hideFromAccessibility() }) { chrome() }
                AnimatedVisibility(expanded,
                    enter = fadeIn(tween(if (motion) 220 else 0)) + slideInVertically(tween(if (motion) 420 else 0, easing = FastOutSlowInEasing)) { it / 8 },
                    exit = fadeOut(tween(if (motion) 230 else 0)) + slideOutVertically(tween(if (motion) 320 else 0, easing = FastOutSlowInEasing)) { it / 5 }) {
                    CompositionLocalProvider(LocalPlayerVisibility provides this, LocalPlayerDrag provides controller) {
                        Box(Modifier.fillMaxSize().graphicsLayer { translationY = drag }
                            .clip(RoundedCornerShape(if (drag > 0 && motion) 18.dp else 0.dp))
                            .background(SpokifyInk).nestedScroll(nested)
                            .pointerInput(expanded) {
                                if (!expanded) awaitPointerEventScope {
                                    // Exit content remains drawn for the transition, but its
                                    // moving controls must stop accepting input immediately.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }.semantics { paneTitle = "Now playing"; if (!expanded) hideFromAccessibility() }) { player() }
                    }
                }
            }
        }
        }
    }
}

@Composable
fun MotionMiniPlayer(content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(!LocalPlayerExpanded.current, enter = fadeIn(tween(if (motion) 200 else 0)) + expandVertically(tween(if (motion) 320 else 0)),
        exit = fadeOut(tween(if (motion) 120 else 0)) + shrinkVertically(tween(if (motion) 320 else 0))) {
        CompositionLocalProvider(LocalPlayerVisibility provides this, content = content)
    }
}

fun Modifier.playerArtwork(trackId: String): Modifier = composed {
    val shared = LocalSharedPlayer.current
    val visibility = LocalPlayerVisibility.current
    val motion = LocalMotionEnabled.current
    if (shared == null || visibility == null || trackId.isEmpty() || !motion) this
    else with(shared) {
        sharedElement(rememberSharedContentState("player-art:$trackId"), visibility,
            boundsTransform = { _, _ -> tween(420, easing = FastOutSlowInEasing) })
    }
}

/** Button/action dismissal waits for the sheet's hide animation before removing its window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotionBottomSheet(onDismiss: () -> Unit, color: Color = SpokifyElevated,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch { state.hide(); callback() }
        }
    }
    ModalBottomSheet(onDismissRequest = { callback() }, sheetState = state, containerColor = color) { content(dismiss) }
}

/** Selected playback modes retain a dot as well as colour, matching the phone reference. */
@Composable
fun PlaybackModeButton(selected: Boolean, icon: Int, description: String, onClick: () -> Unit, badge: String? = null) {
    val tint by animateColorAsState(if (selected) SpokifyGreen else Color.White, motionTween(160), label = "playback mode tint")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        MotionIconButton(onClick = onClick) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), description, Modifier.size(25.dp), tint = tint)
                badge?.let { Text(it, color = tint, fontSize = 9.sp) }
            }
        }
        AnimatedVisibility(selected, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            enter = fadeIn(motionTween(140)) + scaleIn(motionTween(140)), exit = fadeOut(motionTween(100))) {
            Box(Modifier.size(3.dp).background(SpokifyGreen, CircleShape))
        }
    }
}
