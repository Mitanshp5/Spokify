package com.mitansh.sidea.data.offline

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.mitansh.sidea.playback.sabr.SabrRoutingDataSourceFactory
import java.io.IOException

/**
 * Cache keys are the stable recording id, independent of byte range, so a completed download and
 * its playback always address the same bytes and adaptive renditions can never share a key.
 */
@UnstableApi
internal object OfflineCacheKeys : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String =
        OfflineRoutePolicy.trackId(dataSpec.uri.toString()) ?: dataSpec.uri.toString()
}

/**
 * Playback factory: reads a completed offline item from cache, otherwise delegates to `upstream`.
 *
 * The offline branch has no upstream data source, so it can only ever serve bytes that are already
 * cached; anything incomplete keeps streaming through the normal resolver.
 */
@UnstableApi
internal class OfflineRoutingDataSourceFactory(
    private val cache: Cache,
    private val hasCompleteMedia: (String) -> Boolean,
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    private val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(OfflineCacheKeys)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

    override fun createDataSource(): DataSource = RoutingDataSource(upstream.createDataSource())

    private inner class RoutingDataSource(private val streaming: DataSource) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val id = OfflineRoutePolicy.trackId(dataSpec.uri.toString())
            val offline = id != null && hasCompleteMedia(id)
            val source = (if (offline) cacheFactory.createDataSource() else streaming).also { active = it }
            listeners.forEach(source::addTransferListener)
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (active ?: throw IOException("Data source is not open")).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

        override fun close() {
            val source = active
            active = null
            source?.close()
        }
    }
}

/**
 * Download factory that resolves one rendition per download attempt and pins it, so retries inside
 * a task can never append bytes from a different format. Cross-restart resumption is dropped by
 * [OfflineRepository] before downloads resume, so incompatible ranges are never combined.
 */
@UnstableApi
internal class OfflineDownloaderFactory(
    context: Context,
    private val cache: Cache,
    private val resolve: (String) -> Uri,
) : DownloaderFactory {

    private val upstream = SabrRoutingDataSourceFactory(context.applicationContext)

    override fun createDownloader(request: DownloadRequest): Downloader {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(OfflineCacheKeys)
            .setUpstreamDataSourceFactory(PinningDataSourceFactory(upstream, resolve))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ProgressiveDownloader(request.toMediaItem(MediaItem.Builder()), cacheFactory)
    }
}

/** Resolves the stable `sidea-track` uri once per instance and reuses it for every open. */
@UnstableApi
internal class PinningDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val resolve: (String) -> Uri,
) : DataSource.Factory {

    @Volatile
    private var pinned: Uri? = null

    private fun pinnedUri(id: String): Uri {
        pinned?.let { return it }
        return resolve(id).also { pinned = it }
    }

    override fun createDataSource(): DataSource = ResolvingDataSource(delegate.createDataSource(), ::pinnedUri)

    private class ResolvingDataSource(
        private val delegate: DataSource,
        private val resolve: (String) -> Uri,
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            if (uri.scheme != OfflineRoutePolicy.SCHEME) return delegate.open(dataSpec)
            val id = OfflineRoutePolicy.trackId(uri.toString()) ?: throw IOException("Missing track id")
            return delegate.open(dataSpec.buildUpon().setUri(resolve(id)).build())
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() = delegate.close()
    }
}
