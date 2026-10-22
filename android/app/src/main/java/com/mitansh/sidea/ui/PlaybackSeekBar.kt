package com.mitansh.sidea.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Thin music-player track with a full touch target and accessibility seeking. */
@Composable
fun PlaybackSeekBar(value: Float, maximum: Float, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val range by rememberUpdatedState(maximum.coerceAtLeast(1f))
    var dragging by remember { mutableStateOf(false) }
    val radius by animateFloatAsState(if (dragging) 7f else 4f, motionTween(100), label = "seek thumb")
    val thickness by animateFloatAsState(if (dragging) 4f else 3f, motionTween(100), label = "seek track")
    Canvas(Modifier.fillMaxWidth().height(36.dp).focusable().semantics {
        contentDescription = "Playback position"
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, maximum), 0f..maximum)
        setProgress { target -> change(target.coerceIn(0f, range)); finish(); true }
    }.pointerInput(Unit) {
        detectTapGestures { point -> change((point.x / size.width * range).coerceIn(0f, range)); finish() }
    }.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { point -> dragging = true; change((point.x / size.width * range).coerceIn(0f, range)) },
            onHorizontalDrag = { event, _ -> event.consume(); change((event.position.x / size.width * range).coerceIn(0f, range)) },
            onDragEnd = { dragging = false; finish() }, onDragCancel = { dragging = false; finish() },
        )
    }) {
        val fraction = (value / maximum.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val y = size.height / 2
        drawLine(Color.White.copy(alpha = .25f), Offset(0f, y), Offset(size.width, y), thickness.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White, Offset(0f, y), Offset(size.width * fraction, y), thickness.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White, radius.dp.toPx(), Offset(size.width * fraction, y))
    }
}
