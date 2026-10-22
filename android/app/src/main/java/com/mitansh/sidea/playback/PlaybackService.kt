package com.mitansh.sidea.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.history.HistoryPolicy
import com.mitansh.sidea.data.history.ListeningSession
import com.mitansh.sidea.data.library.LibraryRepository
import com.mitansh.sidea.data.library.ListeningStatsRepository
import com.mitansh.sidea.data.library.SideADatabase
import com.mitansh.sidea.data.offline.OfflineRepository
import com.mitansh.sidea.data.similar.MusicBrainzRepository
import com.mitansh.sidea.data.similar.NeighbourhoodRadio
import com.mitansh.sidea.data.similar.SimilarityRepository
import com.mitansh.sidea.data.youtube.YouTubeCatalog
import com.mitansh.sidea.data.youtube.SongRadioRepository
import com.mitansh.sidea.data.youtube.SongRadioMixer
import com.mitansh.sidea.data.youtube.TasteProfile
import com.mitansh.sidea.playback.sabr.SabrRoutingDataSourceFactory
import com.mitansh.sidea.playback.feedback.ArtistFeedbackStore
import com.mitansh.sidea.playback.feedback.PlaybackFeedbackPolicy
import com.mitansh.sidea.playback.feedback.SharedPreferencesFeedbackStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import android.net.Uri
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.SettableFuture
import com.mitansh.sidea.MainActivity
import com.mitansh.sidea.R

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var library: LibraryRepository
    private lateinit var feedback: ArtistFeedbackStore
    private lateinit var stats: ListeningStatsRepository
    private var activeItem: MediaItem? = null
    private var activeIndex = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listening = ListeningSession()
    private var recorded = false
    private val tracksById = TrackMetadataRegistry()
    private val queue = PlaybackQueueCoordinator()
    private val radio = SongRadioRepository()
    private val catalog = YouTubeCatalog()
    private val neighbourhood = NeighbourhoodRadio(MusicBrainzRepository(), SimilarityRepository())
    // The preferences and the snapshot need a context, and a service's base context is not
    // available while the fields are initialised. They are therefore built in onCreate.
    private lateinit var radioPreferences: com.mitansh.sidea.data.radio.RadioPreferences
    private lateinit var queueSnapshots: QueueSnapshotStore
    private var restoringQueue = false
    private val neighbourhoodCache = java.util.concurrent.ConcurrentHashMap<String, List<Track>>()
    private val earlySkips = mutableMapOf<String, Int>()
    private var radioJob: Job? = null
    private var radioRetryAt = 0L
    private var radioFailures = 0
    private val snapshotWriteMutex = kotlinx.coroutines.sync.Mutex()
    private var snapshotRevision = 0L
    private var writtenSnapshotRevision = 0L
    private val recovery = PlaybackRecovery()
    private var recoveryJob: Job? = null
    private var waitingForNetwork: Pair<PlaybackRecovery.Ticket, androidx.media3.common.PlaybackException>? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private lateinit var quietNotifications: QuietPlaybackNotifications
    private var likedUids = emptySet<String>()
    private var savingFromNotification = false
    private val saveCommand = SessionCommand("com.mitansh.sidea.SAVE_LIKED_SONG", Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        // The base context is only available from here on.
        radioPreferences = com.mitansh.sidea.data.radio.RadioPreferences.of(this)
        queueSnapshots = QueueSnapshotStore(this)
        val db = SideADatabase.get(this)
        library = LibraryRepository(db.historyDao(), db.favoritesDao())
        feedback = ArtistFeedbackStore(SharedPreferencesFeedbackStorage(this))
        stats = ListeningStatsRepository(db.listeningStatsDao())
        quietNotifications = QuietPlaybackNotifications.get(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(
                OfflineRepository.get(this).playbackDataSourceFactory(
                    ResolvingDataSourceFactory(SabrRoutingDataSourceFactory(this)) { id ->
                        val track = tracksById[id] ?: throw IllegalStateException("Track metadata unavailable")
                        Uri.parse(kotlinx.coroutines.runBlocking(Dispatchers.IO) { catalog.resolveAudio(track).uri })
                    })))
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                quietNotifications.setPlaybackActive(PlaybackQuietPolicy.isActive(player.playWhenReady,
                    player.mediaItemCount, player.playbackState, player.playbackSuppressionReason))
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                clearRecovery()
                if (PlaybackFeedbackPolicy.isNegativeFeedback(
                        PlaybackFeedbackPolicy.changeFor(reason),
                        listening.accumulatedMs,
                        PlaybackFeedbackPolicy.directionFor(activeIndex, player.currentMediaItemIndex))) {
                    activeItem?.let { previous ->
                        val artist = previous.mediaMetadata.artist?.toString().orEmpty()
                        feedback.recordSkip(artist)
                        earlySkips[previous.mediaId] = ((earlySkips[previous.mediaId] ?: 0) + 1).coerceAtMost(10)
                        val skipped = trackOf(previous)
                        scope.launch { stats.recordSkip(skipped) }
                    }
                }
                activeItem = item
                activeIndex = player.currentMediaItemIndex
                listening.startTrack(player.currentPosition); recorded = false
                refreshMediaButtons()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { refreshMediaButtons() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) { recordIfQualified(); listening.onPaused() }
                saveQueueSnapshot()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) cancelPendingRecovery()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                    reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    flushPrevious(oldPosition)
                    // A song that reached its end was listened to, even though the play itself was
                    // already recorded when it qualified.
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        oldPosition.mediaItem?.let { recordCompletion(it) }
                    }
                }
                if (reason == Player.DISCONTINUITY_REASON_SEEK) listening.onSeek(newPosition.positionMs)
                saveQueueSnapshot()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val ids = (0 until player.mediaItemCount).mapTo(mutableSetOf()) { player.getMediaItemAt(it).mediaId }
                tracksById.retain(ids)
                saveQueueSnapshot()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) recordIfQualified(true)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                recoverFromPlaybackError(error)
            }
        })
        val openPlayer = PendingIntent.getActivity(this, 40,
            Intent(this, MainActivity::class.java).putExtra("open_player", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_status) })
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer).setCallback(object : MediaSession.Callback {
            override fun onConnectAsync(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                val base = MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession, controller).build()
                val commands = base.availableSessionCommands.buildUpon()
                if (controller.isTrusted || controller.packageName == packageName) commands.add(saveCommand)
                return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession, controller)
                    .setAvailableSessionCommands(commands.build()).build())
            }
            override fun onCustomCommand(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction != saveCommand.customAction) return super.onCustomCommand(mediaSession, controller, command, args)
                val track = player.currentMediaItem?.let(::trackOf)
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                if (savingFromNotification) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                savingFromNotification = true
                refreshMediaButtons()
                val result = SettableFuture.create<SessionResult>()
                scope.launch {
                    try {
                        library.toggleFavorite(track)
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (cancel: kotlinx.coroutines.CancellationException) { result.cancel(false); throw cancel
                    } catch (_: Exception) { result.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                    } finally { savingFromNotification = false; refreshMediaButtons() }
                }
                return result
            }
            override fun onPlayerInteractionFinished(mediaSession: MediaSession, controllerInfo: MediaSession.ControllerInfo,
                                                     playerCommands: Player.Commands) {
                if (playerCommands.contains(Player.COMMAND_PREPARE)) clearRecovery()
                if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) && player.playWhenReady && player.playerError != null) {
                    clearRecovery()
                    player.prepare()
                }
            }
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                return Futures.immediateFuture(mediaItems.map(::prepareItem))
            }
            override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                recordIfQualified()
                clearRecovery()
                radioJob?.cancel(); radioJob = null
                radioRetryAt = 0L; radioFailures = 0; earlySkips.clear()
                activeItem = null; activeIndex = -1
                queue.begin(mediaItems.map(::trackOf), startIndex)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map(::prepareItem), startIndex, startPositionMs))
            }
        }).build()
        refreshMediaButtons()
        scope.launch {
            library.observeFavorites().collect { favorites ->
                likedUids = favorites.mapTo(mutableSetOf()) { it.uid }
                refreshMediaButtons()
            }
        }
        scope.launch {
            while (true) {
                quietNotifications.heartbeat()
                if (player.isPlaying) {
                    recordIfQualified()
                    maybeFetchRadio()
                    saveQueueSnapshot()
                }
                delay(1_000)
            }
        }
        registerNetworkCallback()
        restoreQueueSnapshot()
        // Songs that qualified earlier may still be waiting for an allowed network.
        scope.launch { OfflineRepository.get(this@PlaybackService).flushPendingAutomatic() }
    }

    private fun refreshMediaButtons() {
        if (!::session.isInitialized) return
        val liked = player.currentMediaItem?.let(::trackOf)?.uid in likedUids
        session.setMediaButtonPreferences(listOf(
            CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                .setDisplayName(if (player.shuffleModeEnabled) "Turn shuffle off" else "Turn shuffle on")
                // Platform/legacy controllers may not support secondary slots.
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
            CommandButton.Builder(if (liked) CommandButton.ICON_CHECK_CIRCLE_FILLED else CommandButton.ICON_PLUS_CIRCLE_UNFILLED)
                .setSessionCommand(saveCommand)
                .setDisplayName(if (liked) "Remove from Liked Songs" else "Add to Liked Songs")
                .setEnabled(player.currentMediaItem != null && !savingFromNotification)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
        ))
    }

    private fun recordIfQualified(completed: Boolean = false) {
        val item = player.currentMediaItem ?: return
        listening.onPlayingPosition(player.currentPosition)
        recordTrack(item, player.duration, completed)
    }

    /** Refresh failed sources while online; only an actual offline connection waits for a network. */
    private fun recoverFromPlaybackError(error: androidx.media3.common.PlaybackException) {
        val failedId = player.currentMediaItem?.mediaId ?: return
        recoveryJob?.cancel()
        waitingForNetwork = null
        val kind = PlaybackFailurePolicy.kind(error)
        android.util.Log.w("SpokifyPlayback", "Playback failure code=${error.errorCode} kind=$kind http=${PlaybackFailurePolicy.httpStatus(error)} recording=$failedId")
        tracksById[failedId]?.let(catalog::invalidate)
        if (!player.playWhenReady) return
        val ticket = when (val action = recovery.plan(kind,
            com.mitansh.sidea.core.Connectivity.isOnline(this), failedId, player.currentMediaItemIndex)) {
            is PlaybackRecovery.Action.WaitForNetwork -> { waitingForNetwork = action.ticket to error; return }
            is PlaybackRecovery.Action.Retry -> action.ticket
            PlaybackRecovery.Action.Stop -> return
        }
        recoveryJob = scope.launch {
            delay(ticket.delayMs)
            if (!recovery.isCurrent(ticket, player.currentMediaItem?.mediaId, player.currentMediaItemIndex) ||
                !player.playWhenReady || player.playerError !== error) return@launch
            // prepare retains the current item/position. Never replace the timeline or force play after a pause.
            player.prepare()
        }
    }

    private fun cancelPendingRecovery() {
        recoveryJob?.cancel(); recoveryJob = null
        waitingForNetwork = null
        recovery.cancel()
    }

    private fun clearRecovery() {
        cancelPendingRecovery()
        recovery.reset()
    }

    private fun resumeAfterNetworkRecovery() {
        val (ticket, error) = waitingForNetwork ?: return
        if (!com.mitansh.sidea.core.Connectivity.isOnline(this)) return
        waitingForNetwork = null
        if (recovery.isCurrent(ticket, player.currentMediaItem?.mediaId, player.currentMediaItemIndex) &&
            player.playWhenReady && player.playerError === error) recoverFromPlaybackError(error)
    }

    /**
     * Resumes the current track once the network is back.
     *
     * Registered as a connectivity callback rather than a poll, so a dropped connection costs
     * nothing while it lasts and playback restarts as soon as it returns.
     */
    private fun registerNetworkCallback() {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    resumeAfterNetworkRecovery()
                    // The radio stops asking after three failures, so a recovered connection has
                    // to clear that count before the radio can continue.
                    radioFailures = 0
                    radioRetryAt = 0L
                    // Songs that qualified earlier on a metered network are saved now.
                    OfflineRepository.get(this@PlaybackService).flushPendingAutomatic()
                }
            }
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                scope.launch { resumeAfterNetworkRecovery() }
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
    }

    private fun flushPrevious(position: Player.PositionInfo) {
        val item = position.mediaItem ?: return
        if (player.isPlaying) listening.onPlayingPosition(position.positionMs)
        recordTrack(item, item.mediaMetadata.durationMs ?: 0L, completed = false)
    }

    private fun recordTrack(item: MediaItem, durationMs: Long, completed: Boolean) {
        val duration = durationMs.coerceAtLeast(0)
        if (recorded || !HistoryPolicy.shouldRecord(listening.accumulatedMs, duration, completed)) return
        recorded = true
        val track = trackOf(item).copy(durationMs = duration)
        scope.launch {
            library.recordPlayed(track)
            feedback.recordQualified(track.artist)
            stats.recordPlay(track, completed)
            val offline = OfflineRepository.get(this@PlaybackService)
            // Only listens inside the recent window count towards automatic saving, so a song
            // that was played a lot months ago is not treated as frequently played now.
            val recentPlays = stats.recentPlayCount(track, OfflineRepository.RECENT_WINDOW_MS)
            offline.onQualifiedPlay(track, recentPlays)
            // A song that qualified earlier may still be waiting for an allowed network.
            offline.flushPendingAutomatic()
        }
    }

    /**
     * Records that a track was listened to from start to finish.
     *
     * The play itself is already recorded by the time a song ends, so the completion is counted
     * separately. Without this the ranking never learns that a song was actually finished, because
     * the natural transition flushes the previous track as incomplete.
     */
    private fun recordCompletion(item: MediaItem) {
        val track = trackOf(item)
        scope.launch { stats.recordCompletion(track) }
    }

    /**
     * Saves the queue and the position.
     *
     * The snapshot is written off the main thread, and only when the queue is not being restored,
     * so a restore cannot overwrite the state it is reading.
     */
    private fun saveQueueSnapshot() {
        if (restoringQueue) return
        val items = playerTimeline()
        if (items.isEmpty()) return
        val current = player.currentMediaItemIndex
        if (current < 0) return
        val position = player.currentPosition
        val seed = queue.seedTrack()
        val revision = ++snapshotRevision
        scope.launch(Dispatchers.IO) {
            snapshotWriteMutex.lock()
            try {
                if (revision > writtenSnapshotRevision) {
                    queueSnapshots.save(QueueSnapshotStore.Snapshot(items, current, position, seed))
                    writtenSnapshotRevision = revision
                }
            } finally {
                snapshotWriteMutex.unlock()
            }
        }
    }

    /**
     * Restores the queue that was playing when the app was last used.
     *
     * The queue is put back without starting playback, so the app opens where it left off rather
     * than making noise by itself. The radio session is restarted from the same seed, so the
     * recommendations continue where they stopped.
     */
    private fun restoreQueueSnapshot() {
        val snapshot = queueSnapshots.load() ?: return
        if (snapshot.items.isEmpty()) return
        restoringQueue = true
        try {
            snapshot.items.forEach { tracksById.put(it) }
            val items = snapshot.items.map(::mediaItemFor)
            player.setMediaItems(items, snapshot.currentIndex, snapshot.positionMs)
            player.prepare()
            queue.begin(snapshot.items, snapshot.currentIndex)
            snapshot.seed?.let { queue.setSeed(it) }
        } finally {
            restoringQueue = false
        }
    }

    /**
     * Builds the preference profile the mixer ranks with.
     *
     * Counts come from real listening, so a track the user has finished or replayed is promoted
     * and the queue keeps the variety of what was played most recently.
     */
    private suspend fun tasteProfile(): TasteProfile {
        val rows = stats.all()
        return TasteProfile(
            playCounts = rows.associate { it.uid to it.playCount },
            completedCounts = rows.associate { it.uid to it.completedCount },
            artistPlayCounts = rows.groupingBy { it.artistKey }.fold(0) { total, row -> total + row.playCount },
            recentArtists = rows.sortedByDescending { it.lastPlayedAtEpochMs }
                .take(RECENT_ARTIST_WINDOW)
                .map { it.artistKey }
                .filter { it.isNotEmpty() },
        )
    }

    /**
     * The songs that listeners pair with the seed, resolved to playable tracks.
     *
     * The official app's radio model is server-side and cannot be copied, so the neighbourhood
     * comes from a public collaborative-filtering dataset instead. The lookup happens off the
     * playback path and its result is cached, so a slow or unreachable service never delays
     * playback. A failure here simply means fewer candidates; the ordinary feed still fills the
     * queue.
     */
    private suspend fun neighbourhoodTracks(seed: Track): List<Track> {
        val cached = neighbourhoodCache[seed.uid]
        if (cached != null) return cached
        val candidates = withContext(Dispatchers.IO) {
            runCatching { neighbourhood.candidatesFor(seed) }.getOrElse { emptyList() }
        }
        if (candidates.isEmpty()) return emptyList()
        val resolved = candidates.mapNotNull { candidate ->
            when (candidate.reason) {
                NeighbourhoodRadio.Reason.SIMILAR_TRACK -> {
                    if (candidate.title.isBlank()) return@mapNotNull null
                    val track = searchPlayable(candidate.artist, candidate.title) ?: return@mapNotNull null
                    track
                }
                NeighbourhoodRadio.Reason.SIMILAR_ARTIST -> {
                    // An artist neighbour has no title of its own, so its popular tracks are used.
                    searchPlayable(candidate.artist, "") ?: return@mapNotNull null
                }
            }
        }
        // The cache is bounded, so a long session cannot grow it without limit.
        if (neighbourhoodCache.size >= NEIGHBOURHOOD_CACHE_LIMIT) {
            neighbourhoodCache.keys.take(NEIGHBOURHOOD_CACHE_LIMIT / 4).forEach { neighbourhoodCache.remove(it) }
        }
        neighbourhoodCache[seed.uid] = resolved
        return resolved
    }

    /** Searches for one playable track, preferring an exact title and artist match. */
    private suspend fun searchPlayable(artist: String, title: String): Track? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        val results = runCatching { catalog.search(query) }.getOrElse { emptyList() }
        if (results.isEmpty()) return null
        if (title.isBlank()) return results.first()
        val normalizedTitle = title.lowercase()
        return results.firstOrNull { it.title.lowercase() == normalizedTitle } ?: results.first()
    }

    private suspend fun maybeFetchRadio() {
        if (!radioPreferences.autoplayEnabled) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF || player.mediaItemCount == 0 ||
            player.mediaItemCount - player.currentMediaItemIndex > 3 || radioJob?.isActive == true ||
            radioFailures >= MAX_RADIO_FAILURES || System.currentTimeMillis() < radioRetryAt) return
        val timeline = playerTimeline()
        val request = queue.request(timeline) ?: return
        val job = scope.launch {
            try {
                val page = radio.fetch(request.seed, request.continuation)
                val favourites = library.observeFavorites().first().flatMapTo(mutableSetOf()) { listOf(it.id, it.uid) }
                // A song the user hid never comes back.
                val hidden = radioPreferences.hiddenTracks()
                val taste = tasteProfile()
                val artistWeights = feedback.weights()
                fun mix(tracks: List<Track>) = SongRadioMixer.mix(tracks.filter { it.id !in hidden }, request.seed, favourites, earlySkips,
                    artistWeights = artistWeights,
                    excludedIds = request.excluded, excludedTracks = timeline, limit = 30, taste = taste)
                var candidates = mix(page.tracks)
                // A usable provider mix should not wait for secondary metadata and search calls.
                if (candidates.size < 8 || candidates.map { SongRadioMixer.normalizeArtist(it.artist) }.distinct().size < 3) {
                    candidates = mix(page.tracks + neighbourhoodTracks(request.seed))
                }
                queue.completeAndAppend(request, candidates, playerTimeline(),
                    repeatOff = player.repeatMode == Player.REPEAT_MODE_OFF, continuation = page.continuation) { fresh ->
                    fresh.forEach(tracksById::put)
                    val items = fresh.map(::mediaItemFor)
                    val ended = player.playbackState == Player.STATE_ENDED && player.playWhenReady
                    player.addMediaItems(items)
                    if (ended && items.isNotEmpty()) {
                        player.seekToNextMediaItem()
                        player.play()
                    }
                }
                radioFailures = 0; radioRetryAt = 0L
            } catch (error: kotlinx.coroutines.CancellationException) {
                queue.failed(request)
                throw error
            } catch (_: Exception) {
                queue.failed(request)
                radioFailures++
                radioRetryAt = System.currentTimeMillis() + 30_000L
            }
        }
        radioJob = job
        job.invokeOnCompletion { if (radioJob === job) radioJob = null }
    }

    private fun playerTimeline(): List<Track> = (0 until player.mediaItemCount).map { index ->
        trackOf(player.getMediaItemAt(index))
    }

    private fun trackOf(item: MediaItem): Track {
        val metadata = item.mediaMetadata
        return Track(item.mediaId, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
            metadata.durationMs ?: 0L, metadata.artworkUri?.toString())
    }

    private fun prepareItem(item: MediaItem): MediaItem {
        tracksById.put(trackOf(item))
        return item.buildUpon().setUri(Uri.Builder().scheme("sidea-track").appendPath(item.mediaId).build())
            .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(
                com.mitansh.sidea.core.ArtworkUrl.forDisplay(item.mediaMetadata.artworkUri?.toString())?.let(Uri::parse)).build()).build()
    }

    private fun mediaItemFor(track: Track): MediaItem = MediaItem.Builder().setMediaId(track.id)
        .setUri(Uri.Builder().scheme("sidea-track").appendPath(track.id).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setDurationMs(track.durationMs).setArtworkUri(com.mitansh.sidea.core.ArtworkUrl.forDisplay(track.artworkUrl)?.let(Uri::parse)).build()).build()

    private companion object {

        const val MAX_RADIO_FAILURES = 3

        /** How many recently played artists inform the queue's variety. */
        const val RECENT_ARTIST_WINDOW = 10

        /** How many seeds keep their neighbourhood cached. */
        const val NEIGHBOURHOOD_CACHE_LIMIT = 40
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        cancelPendingRecovery()
        if (::quietNotifications.isInitialized) quietNotifications.setPlaybackActive(false)
        val callback = networkCallback
        networkCallback = null
        if (callback != null) {
            val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            runCatching { manager?.unregisterNetworkCallback(callback) }
        }
        scope.cancel()
        session.release(); player.release()
        super.onDestroy()
    }
}
