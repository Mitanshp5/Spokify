package com.mitansh.sidea.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.mitansh.sidea.R
import com.mitansh.sidea.data.offline.OfflineArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlayerArtwork(trackId: String, url: String?, description: String?, modifier: Modifier, onColor: ((Color) -> Unit)? = null, sizePx: Int = 1200) {
    val currentUrl by rememberUpdatedState(url)
    val callback by rememberUpdatedState(onColor)
    Crossfade(url, Modifier.playerArtwork(trackId).then(modifier), animationSpec = motionTween(240), label = "song artwork") { imageUrl ->
        Artwork(imageUrl, description, Modifier.fillMaxSize(), onColor = if (onColor == null) null else { color ->
            if (imageUrl == currentUrl) callback?.invoke(color)
        }, sizePx = sizePx)
    }
}

@Composable
fun Artwork(url: String?, description: String?, modifier: Modifier = Modifier, onColor: ((Color) -> Unit)? = null, sizePx: Int = 256) {
    val context = LocalContext.current
    val revision by OfflineArtworkStore.changes.collectAsStateWithLifecycle()
    val model = remember(url, sizePx, revision) {
        url?.takeIf { it.isNotBlank() }?.let { ArtworkRequest(it, sizePx, revision) }
    }
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onColor)
    val motion = LocalMotionEnabled.current
    val request = remember(context, model, onColor != null, motion) {
        ImageRequest.Builder(context).data(model)
            .memoryCacheKey(model?.let { "${it.cacheKey}:${onColor != null}" })
            .allowHardware(onColor == null).crossfade(if (motion) 180 else 0).build()
    }
    Box(modifier.background(SpokifyElevated), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music), null, Modifier.size(28.dp), tint = Color(0xFF777777))
        if (model != null) {
            AsyncImage(
                model = request,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { success ->
                    if (callback != null) scope.launch {
                        val color = withContext(Dispatchers.Default) {
                            runCatching {
                                val original = success.result.image.toBitmap()
                                // A cached hardware bitmap must be copied before software sampling.
                                val readable = if (original.config == android.graphics.Bitmap.Config.HARDWARE)
                                    original.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else original
                                val bitmap = android.graphics.Bitmap.createScaledBitmap(readable, 64, 64, true)
                                var red = 0L; var green = 0L; var blue = 0L; var count = 0
                                var allRed = 0L; var allGreen = 0L; var allBlue = 0L
                                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                    val pixel = bitmap.getPixel(x, y)
                                    val r = android.graphics.Color.red(pixel)
                                    val g = android.graphics.Color.green(pixel)
                                    val b = android.graphics.Color.blue(pixel)
                                    allRed += r; allGreen += g; allBlue += b
                                    if (maxOf(r, g, b) - minOf(r, g, b) < 25) continue
                                    red += r; green += g; blue += b; count++
                                }
                                if (count == 0) { red = allRed; green = allGreen; blue = allBlue; count = bitmap.width * bitmap.height }
                                val result = Color(red = red / (count * 255f) * .55f, green = green / (count * 255f) * .55f, blue = blue / (count * 255f) * .55f)
                                if (bitmap !== readable) bitmap.recycle()
                                if (readable !== original) readable.recycle()
                                result
                            }.onFailure { android.util.Log.w("SpokifyArtwork", "Could not sample artwork colour", it) }.getOrNull()
                        }
                        color?.let { callback?.invoke(it) }
                    }
                },
            )
        }
    }
}
