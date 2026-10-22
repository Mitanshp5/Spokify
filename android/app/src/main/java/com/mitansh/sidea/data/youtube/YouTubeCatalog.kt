package com.mitansh.sidea.data.youtube

import com.mitansh.sidea.core.AudioResolutionCache
import com.mitansh.sidea.core.ResolvedAudio
import com.mitansh.sidea.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

class YouTubeCatalog(
    private val resolutions: AudioResolutionCache = SharedResolutionCache.instance,
) {
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank())
        SearchResultMapper.toTracks(searchExtractor(query).apply { fetchPage() }.initialPage.items)
    }

    internal fun searchExtractor(query: String): org.schabi.newpipe.extractor.search.SearchExtractor {
        val factory = ServiceList.YouTube.searchQHFactory
        val songs = factory.availableContentFilter.filterGroups
            .flatMap { it.filterItems.toList() }
            .first { it.name == "music_songs" }
        return ServiceList.YouTube.getSearchExtractor(
            factory.fromQuery(query, listOf(songs), emptyList())
        )
    }

    suspend fun resolve(track: Track): String = resolveAudio(track).uri

    /**
     * Resolves playable audio for [track], reusing a still-valid resolution when there is one.
     *
     * Media3 reopens the data source on every seek and for every track it prepares ahead, so
     * resolving on every open meant paying two upstream round-trips each time. The cache keeps a
     * resolution only until it is close to its upstream expiry.
     */
    suspend fun resolveAudio(track: Track): PlaybackAudio {
        val cached = resolutions.get(cacheKey(track), System.currentTimeMillis())
        if (cached != null) return cached.toPlaybackAudio()
        val resolved = withContext(Dispatchers.IO) { ReelPlaybackResolver.resolve(track) }
        resolutions.put(
            cacheKey(track),
            ResolvedAudio(resolved.uri, resolved.mimeType, resolved.bitrateBps, resolved.expiresAtEpochMs),
            System.currentTimeMillis(),
        )
        return resolved
    }

    fun invalidate(track: Track) {
        resolutions.invalidate(cacheKey(track))
    }

    private fun cacheKey(track: Track) = track.id

    /** The cache this catalog resolves through, so a test can check that it is shared. */
    internal val resolutionsForTest: AudioResolutionCache get() = resolutions

    private fun ResolvedAudio.toPlaybackAudio() = PlaybackAudio(url, mimeType, bitrateBps, false, expiresAtEpochMs)

    companion object {
        fun initialize() = NewPipe.init(SideADownloader())
    }
}

/**
 * The one resolution cache for the process.
 *
 * The playback service and the download manager each build their own catalog, and a resolution
 * that one of them already holds is useful to the other: the same song is often played and then
 * saved. A per-instance cache would make each of them resolve the same song again, which costs
 * two upstream round-trips each time.
 */
object SharedResolutionCache {
    val instance: AudioResolutionCache = AudioResolutionCache()
}
