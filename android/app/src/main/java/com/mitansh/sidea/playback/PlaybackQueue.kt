package com.mitansh.sidea.playback

import com.mitansh.sidea.core.Track
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

internal class TrackMetadataRegistry {
    private val tracks = ConcurrentHashMap<String, Track>()
    fun put(track: Track) { tracks[track.id] = track }
    operator fun get(id: String): Track? = tracks[id]
    fun retain(ids: Set<String>) { tracks.keys.retainAll(ids) }
}

/** Coordinates one player queue and one fixed radio seed for a user-selected session. */
internal class PlaybackQueueCoordinator {
    data class Request(val generation: Long, val seed: Track, val continuation: String?, val excluded: Set<String>)
    private val generations = AtomicLong()
    private var generation = 0L
    private var seed: Track? = null
    private val seen = mutableSetOf<String>()
    private var continuation: String? = null
    private var exhausted = false
    private var inFlight = false
    private var noGrowth = 0

    @Synchronized fun begin(tracks: List<Track>, startIndex: Int = 0) {
        generation = generations.incrementAndGet()
        seed = tracks.getOrNull(startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)))
        seen.clear(); seen.addAll(tracks.map { it.id })
        continuation = null; exhausted = false; inFlight = false; noGrowth = 0
    }

    @Synchronized fun begin(track: Track) = begin(listOf(track))

    /** The song the radio is building on, so a restored session can continue from the same seed. */
    @Synchronized fun seedTrack(): Track? = seed

    /** Restores the seed of a session that is being continued. */
    @Synchronized fun setSeed(track: Track) {
        seed = track
    }
    @get:Synchronized val canRequest: Boolean get() = seed != null && !exhausted && !inFlight

    @Synchronized fun request(timeline: List<Track>? = null): Request? {
        if (!canRequest) return null
        timeline?.let { tracks -> seen.addAll(tracks.map { it.id }) }
        inFlight = true
        return Request(generation, seed!!, continuation, seen.toSet())
    }

    @Synchronized fun completeAndAppend(
        request: Request,
        tracks: List<Track>,
        timeline: List<Track>,
        repeatOff: Boolean,
        continuation: String? = null,
        append: (List<Track>) -> Unit,
    ): Boolean {
        if (request.generation != generation || !inFlight) return false
        inFlight = false
        seen.addAll(request.excluded)
        seen.addAll(timeline.map { it.id })
        if (!repeatOff) return false
        val fresh = tracks.filter { it.id !in seen }.distinctBy { it.id }
        if (fresh.isNotEmpty()) append(fresh)
        seen.addAll(fresh.map { it.id })
        noGrowth = if (fresh.isEmpty()) noGrowth + 1 else 0
        this.continuation = continuation
        exhausted = continuation.isNullOrBlank() || noGrowth >= MAX_NO_GROWTH
        return true
    }

    @Synchronized fun failed(request: Request): Boolean {
        if (request.generation != generation || !inFlight) return false
        inFlight = false
        return true
    }

    private companion object { const val MAX_NO_GROWTH = 2 }
}

@UnstableApi
internal class ResolvingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val resolve: (String) -> Uri,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ResolvingDataSource(delegate.createDataSource(), resolve)

    private class ResolvingDataSource(private val delegate: DataSource, private val resolve: (String) -> Uri) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            if (uri.scheme != "sidea-track") return delegate.open(dataSpec)
            val id = uri.lastPathSegment ?: throw IOException("Missing track id")
            val resolved = try { resolve(id) }
            catch (error: Exception) { throw IOException("Unable to resolve audio for track", error) }
            return try { delegate.open(dataSpec.buildUpon().setUri(resolved).build()) }
            catch (error: Exception) { throw IOException("Unable to open audio for track", error) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
        override fun getUri(): Uri? = delegate.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
        override fun close() = delegate.close()
    }
}
