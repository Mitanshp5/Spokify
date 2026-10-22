package com.mitansh.sidea.ui

import android.content.Context
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.mitansh.sidea.core.ArtworkUrl
import com.mitansh.sidea.data.offline.OfflineArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ArtworkRequest(val url: String, val sizePx: Int, val revision: Long) {
    val cacheKey: String get() = "spokify-art:$url:$sizePx:$revision"
}

/** Resolve once in the image pipeline, retaining Coil's normal cached-image path. */
internal class OfflineArtworkInterceptor(private val context: Context) : Interceptor {
    private val resolved = object : LinkedHashMap<ArtworkRequest, Any>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArtworkRequest, Any>?): Boolean = size > 256
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val artwork = request.data as? ArtworkRequest ?: return chain.proceed()
        val cached = synchronized(resolved) { resolved[artwork] }
        val model = cached ?: withContext(Dispatchers.IO) {
            val canonical = ArtworkUrl.forDisplay(artwork.url) ?: return@withContext null
            val local = OfflineArtworkStore.file(context, canonical)
            if (local.isFile) local else {
                // Keep covers viewed before thumbnail sizing available while offline.
                val stored = SingletonImageLoader.get(context).diskCache
                    ?.openSnapshot(canonical)?.use { true } == true
                if (stored) canonical else ArtworkUrl.forDisplay(artwork.url, artwork.sizePx)
            }
        }?.also { synchronized(resolved) { resolved[artwork] = it } }
        return chain.withRequest(request.newBuilder().data(model).build()).proceed()
    }
}
