package com.mitansh.sidea.data.offline

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.youtube.YouTubeCatalog
import com.mitansh.sidea.playback.OfflineDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns offline downloads for the process.
 *
 * State lives in Media3's persistent [DefaultDownloadIndex]; media bytes live in a [SimpleCache]
 * under the app's files directory (never the evictable cache directory) with a [NoOpCacheEvictor]
 * so nothing is deleted behind the user's back. Requests only ever carry the stable `sidea-track`
 * uri plus [OfflineMetadata] in `data`, never a signed media URL.
 *
 * Manual and automatic downloads are separate ownership classes, mirroring the official app's split
 * between its download service and its automatic playable cache: automatic content is bounded by
 * the user's storage limit and may be evicted, while manual downloads are never removed
 * automatically.
 */
@OptIn(UnstableApi::class)
class OfflineRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val cache: Cache =
        SimpleCache(File(appContext.filesDir, MEDIA_DIR), NoOpCacheEvictor(), databaseProvider)
    private val index = DefaultDownloadIndex(databaseProvider)
    private val downloaderFactory = OfflineDownloaderFactory(appContext, cache, ::resolveToUri)
    private val refreshMutex = Mutex()

    @Volatile
    private var snapshot: List<Download> = emptyList()

    @Volatile
    private var completedIds: Set<String> = emptySet()

    private val requested = java.util.concurrent.ConcurrentHashMap<String, Track>()
    private val artworkRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(OfflineState(automaticEnabled = automaticEnabled(), storageLimitBytes = storageLimitBytes()))
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) = refresh()
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
        override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) = refresh()
        override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) = refresh()
    }

    /** Shared with [OfflineDownloadService]; access only from the main thread. */
    val downloadManager: DownloadManager

    init {
        recoverIncomplete()
        val manager = DownloadManager(appContext, index, downloaderFactory)
        manager.setMaxParallelDownloads(1)
        manager.addListener(listener)
        downloadManager = manager
        applyRequirements()
        refresh()
        // Media3 does not dispatch a state-change callback for every progress update.
        scope.launch {
            while (true) {
                delay(1000)
                if (snapshot.any { it.state == Download.STATE_DOWNLOADING }) refresh()
            }
        }
        appContext.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                    refresh()
                    scope.launch { flushPendingAutomatic() }
                }
            }
        )
    }

    /**
     * Applies the network requirement the user's settings ask for.
     *
     * The requirement belongs to the manager rather than to each request, so a transfer that is
     * waiting for an unmetered network resumes as soon as the setting allows it. The setting is a
     * single switch for now: automatic saves follow the metered-data switch, and manual downloads
     * are allowed on any connection, because the user asked for that song explicitly.
     */
    private fun applyRequirements() {
        downloadManager.setRequirements(
            Requirements(Requirements.NETWORK or Requirements.DEVICE_STORAGE_NOT_LOW)
        )
        refresh()
    }

    /**
     * Whether the device is low on battery or in power-saving mode.
     *
     * A song the user asked for is saved even then, because that is what they asked for. A song
     * that is only being saved because it was played often is left for later, so the app does not
     * drain a phone that is trying to last the day.
     */
    fun isBatteryLow(): Boolean {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val powerSave = (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        return powerSave || percent in 0..LOW_BATTERY_PERCENT
    }

    // --- Playback routing ---

    /**
     * Routes completed offline items to read-only cached bytes and everything else to [upstream].
     * This is the only integration playback needs from the offline layer.
     */
    fun playbackDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        OfflineRoutingDataSourceFactory(cache, { id -> findDownload(id)?.let(::isCompleteOnDisk) == true }) { upstream.createDataSource() }

    /** Called on the playback loader, so cold-start routing never depends on a UI refresh. */
    private fun isCompleteOnDisk(download: Download): Boolean {
        if (download.state != Download.STATE_COMPLETED) return false
        val length = if (download.contentLength > 0) download.contentLength else download.getBytesDownloaded()
        return length > 0 && runCatching { cache.isCached(download.request.id, 0L, length) }.getOrDefault(false)
    }

    // --- User actions ---

    /** Queues a download. Automatic requests are gated by [AutomaticDownloadPolicy]. */
    fun enqueue(track: Track, automatic: Boolean = false) {
        val existing = _state.value.items.firstOrNull { it.id == track.id }
        if (existing != null) {
            if (!automatic && existing.automatic) promoteToManual(track.id)
            return
        }
        if (requested.putIfAbsent(track.id, track) != null) return
        if (!automatic) _state.update { it.copy(items = it.items + queuedItem(track)) }
        scope.launch {
            try {
                if (!enqueueInternal(track, automatic, allowExisting = false)) {
                    requested.remove(track.id)
                    refresh()
                }
            } catch (_: Exception) {
                requested.remove(track.id)
                _state.update { state -> state.copy(items = state.items.filterNot { it.id == track.id } + queuedItem(track).copy(status = OfflineStatus.FAILED)) }
            }
        }
    }

    private fun queuedItem(track: Track) = OfflineItem(track.id, track, false, OfflineStatus.QUEUED, 0L, -1L, -1f)

    /**
     * Queues a whole list of songs.
     *
     * A playlist can be saved in one action, so the user does not have to tap every row. The
     * songs are queued one after the other, because the manager downloads one at a time anyway.
     * The result says how many were queued, so the user is told what happened.
     */
    fun enqueueAll(tracks: List<Track>, automatic: Boolean = false, onDone: (Int) -> Unit = {}) {
        scope.launch {
            var queued = 0
            for (track in tracks) {
                if (enqueueInternal(track, automatic, allowExisting = false)) queued++
            }
            onDone(queued)
        }
    }

    /** Cancels a pending download and clears its partial bytes; completed bytes are untouched. */
    fun cancel(id: String) = remove(id)

    /** Removes the item and its downloaded bytes, suppressing immediate automatic requeue. */
    fun remove(id: String) {
        requested.remove(id)
        val item = _state.value.items.firstOrNull { it.id == id }
        if (item?.automatic == true) {
            prefs.edit().putInt(suppressKey(id), prefs.getInt(lastCountKey(id), 0)).apply()
        }
        DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, id, true)
    }

    /** Clears partial bytes and starts the download again from scratch. */
    fun retry(track: Track) {
        scope.launch {
            val existing = findDownload(track.id)
            val automatic = OfflineMetadata.decode(existing?.request?.data)?.automatic == true
            DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, track.id, true)
            enqueueInternal(track, automatic, allowExisting = true)
        }
    }

    /** Turns frequent-play downloads on or off. Off cancels pending automatic jobs only. */
    fun setAutomaticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOMATIC, enabled).apply()
        _state.value = _state.value.copy(automaticEnabled = enabled)
        if (!enabled) scope.launch { cancelPendingAutomatic() }
        else scope.launch { flushPendingAutomatic() }
    }

    /**
     * Whether automatic saves may use a metered connection.
     *
     * Off by default, so automatic saving never spends the user's data unexpectedly. Turning it on
     * lets a song that qualified earlier be saved straight away on mobile data.
     */
    fun setAllowMetered(allowed: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_METERED, allowed).apply()
        _state.value = _state.value.copy(allowMetered = allowed)
        applyRequirements()
        if (allowed) scope.launch { flushPendingAutomatic() }
    }

    fun allowMetered(): Boolean = prefs.getBoolean(KEY_ALLOW_METERED, false)

    /**
     * The songs that must never be saved automatically.
     *
     * A song the user never wants to keep offline stays out of the automatic collection, whatever
     * the play count says. Manual downloads are unaffected.
     */
    fun neverAutomaticTracks(): Set<String> =
        prefs.getString(KEY_NEVER_AUTOMATIC, "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    /** Stops a song from being saved automatically, and forgets it if it was pending. */
    fun setNeverAutomatic(trackId: String, never: Boolean) {
        if (trackId.isBlank()) return
        val current = neverAutomaticTracks().toMutableSet()
        if (never) current += trackId else current -= trackId
        prefs.edit().putString(KEY_NEVER_AUTOMATIC, current.joinToString("\n")).apply()
        if (never) forgetPending(trackId)
    }

    fun isNeverAutomatic(trackId: String): Boolean = trackId in neverAutomaticTracks()

    /**
     * Keeps an automatically saved song.
     *
     * The ownership becomes manual, so the song survives the automatic eviction and no longer
     * counts against the automatic budget.
     *
     * The record is rewritten in the index rather than the download being re-added. Re-adding
     * would merge the request and put a finished download back into the queue, which would fetch
     * the whole song again even though its bytes are already on the device.
     */
    fun promoteToManual(trackId: String) {
        scope.launch { promoteToManualInternal(trackId) }
    }

    private fun promoteToManualInternal(trackId: String) {
        val download = findDownload(trackId) ?: return
        val metadata = OfflineMetadata.decode(download.request.data) ?: return
        if (!metadata.automatic) return
        val original = download.request
        val builder = DownloadRequest.Builder(original.id, original.uri)
            .setMimeType(original.mimeType)
            .setStreamKeys(original.streamKeys)
            .setKeySetId(original.keySetId)
            .setCustomCacheKey(original.customCacheKey)
            .setData(OfflineMetadata.of(metadata.toTrack(trackId), automatic = false).encode())
        // The range is only set when the original had one, because the setter needs both bounds.
        original.byteRange?.let { builder.setByteRange(it.offset, it.length) }
        original.timeRange?.let { builder.setTimeRange(it.startPositionUs, it.durationUs) }
        val request = builder.build()
        // The download keeps its state, its length and its progress; only the ownership changes.
        val promoted = Download(
            request,
            download.state,
            download.startTimeMs,
            download.updateTimeMs,
            download.contentLength,
            download.stopReason,
            download.failureReason,
            DownloadProgress().apply {
                bytesDownloaded = download.getBytesDownloaded()
                percentDownloaded = download.percentDownloaded
            },
        )
        try {
            index.putDownload(promoted)
        } catch (_: Exception) {
            return
        }
        refresh()
    }

    // --- Automatic (frequent-play) integration ---

    /**
     * Queues a download after a qualified listen, honouring suppression, network and the cap.
     *
     * The candidate is remembered even when the request is not made, so a song that qualified on
     * mobile data is still picked up later on an allowed network without needing another listen.
     * The remembered candidates are flushed by [flushPendingAutomatic].
     */
    suspend fun onQualifiedPlay(track: Track, playCount: Int) {
        if (isNeverAutomatic(track.id)) return
        rememberPending(track, playCount)
        withContext(Dispatchers.IO) {
            if (!automaticEnabled()) return@withContext
            if (!AutomaticDownloadPolicy.isQualified(playCount)) return@withContext
            if (findDownload(track.id) != null) return@withContext
            val suppressedAt = prefs.getInt(suppressKey(track.id), 0)
            if (!AutomaticDownloadPolicy.canRequeueOnPlay(playCount, suppressedAt)) return@withContext
            if (!isUnmeteredOnline()) return@withContext
            // A song that is only saved because it was played often waits for a healthier battery.
            if (isBatteryLow()) return@withContext
            if (!AutomaticDownloadPolicy.canQueue(automaticUsage(snapshot), capBytes = storageLimitBytes())) return@withContext
            enqueueInternal(track, automatic = true, allowExisting = false)
        }
    }
    /**
     * Queues the songs that qualified earlier but could not be saved at the time.
     *
     * Called when the network becomes suitable, so a song heard on mobile data is saved later on
     * Wi-Fi without the user having to play it again. Most valuable first, so the budget is spent
     * on the songs the user actually listens to most.
     */
    suspend fun flushPendingAutomatic() {
        withContext(Dispatchers.IO) {
            if (!automaticEnabled()) return@withContext
            if (!isUnmeteredOnline()) return@withContext
            if (isBatteryLow()) return@withContext
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            for (candidate in pendingStore.candidates()) {
                val id = candidate.track.id
                // A song the user never wants to keep offline is not saved.
                if (isNeverAutomatic(id)) {
                    forgetPending(id)
                    continue
                }
                // A song that was played often but long ago is no longer frequently played.
                if (candidate.lastPlayedAtEpochMs < cutoff) {
                    forgetPending(id)
                    continue
                }
                if (!AutomaticDownloadPolicy.isQualified(candidate.playCount)) continue
                if (findDownload(id) != null) {
                    forgetPending(id)
                    continue
                }
                val suppressedAt = prefs.getInt(suppressKey(id), 0)
                if (!AutomaticDownloadPolicy.canRequeueOnPlay(candidate.playCount, suppressedAt)) continue
                if (!AutomaticDownloadPolicy.canQueue(automaticUsage(snapshot), capBytes = storageLimitBytes())) break
                if (enqueueInternal(candidate.track, automatic = true, allowExisting = false)) {
                    forgetPending(id)
                }
            }
        }
    }

    /** The songs that qualified but are not saved yet, most played first. */
    fun pendingAutomaticCount(): Int = pendingStore.size()

    private val pendingStore = PendingAutomaticStore(
        read = { prefs.getString(KEY_PENDING, "").orEmpty() },
        write = { value -> prefs.edit().putString(KEY_PENDING, value).apply() },
    )

    private fun rememberPending(track: Track, playCount: Int) =
        pendingStore.remember(track, playCount, System.currentTimeMillis())

    private fun forgetPending(id: String) = pendingStore.forget(id)

    // --- Storage limit ---

    /** The user's automatic-download budget in bytes. Manual downloads are never counted. */
    fun storageLimitBytes(): Long = prefs.getLong(KEY_LIMIT_BYTES, AutomaticDownloadPolicy.CAP_BYTES)

    /** Changes the automatic-download budget and trims automatic content to fit it. */
    fun setStorageLimitBytes(bytes: Long) {
        val limit = bytes.coerceIn(MIN_LIMIT_BYTES, MAX_LIMIT_BYTES)
        prefs.edit().putLong(KEY_LIMIT_BYTES, limit).apply()
        _state.update { it.copy(storageLimitBytes = limit) }
        scope.launch { enforceAutomaticCap(limit) }
    }

    // --- Internals ---

    private suspend fun enqueueInternal(track: Track, automatic: Boolean, allowExisting: Boolean): Boolean {
        if (!allowExisting && findDownload(track.id) != null) return false
        if (automatic) {
            if (!automaticEnabled()) return false
            if (!isUnmeteredOnline()) return false
            if (!AutomaticDownloadPolicy.canQueue(automaticUsage(snapshot), capBytes = storageLimitBytes())) return false
        }
        val request = DownloadRequest.Builder(track.id, sideaTrackUri(track.id))
            .setCustomCacheKey(track.id)
            .setData(OfflineMetadata.of(track, automatic).encode())
            .build()
        DownloadService.sendAddDownload(appContext, OfflineDownloadService::class.java, request, true)
        return true
    }

    private suspend fun cancelPendingAutomatic() {
        for (download in snapshot) {
            val metadata = OfflineMetadata.decode(download.request.data) ?: continue
            if (AutomaticDownloadPolicy.shouldCancelOnDisable(download.state, metadata.automatic)) {
                DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, download.request.id, true)
            }
        }
    }

    private fun findDownload(id: String): Download? = try {
        index.getDownload(id)
    } catch (_: Exception) {
        null
    }

    private fun resolveToUri(id: String): Uri = Uri.parse(
        runBlocking(Dispatchers.IO) { YouTubeCatalog().resolveAudio(Track(id, "", "")).uri },
    )

    // --- State ---

    private fun refresh() {
        scope.launch {
            refreshMutex.withLock {
                val active = withContext(Dispatchers.Main) { downloadManager.currentDownloads }.associateBy { it.request.id }
                val indexed = loadAll()
                val downloads = (indexed.map { active[it.request.id] ?: it } + active.values.filter { running -> indexed.none { it.request.id == running.request.id } })
                    .map(::repairCompletedProgress)
                downloads.forEach { requested.remove(it.request.id) }
                snapshot = downloads
                completedIds = downloads
                    .filter(::isCompleteOnDisk)
                    .mapTo(mutableSetOf()) { it.request.id }
                enforceAutomaticCap(storageLimitBytes())
                val automaticAllowed = isUnmeteredOnline() && !isBatteryLow()
                withContext(Dispatchers.Main) {
                    for (download in downloads) {
                        if (OfflineMetadata.decode(download.request.data)?.automatic != true) continue
                        if (download.state !in PENDING_STATES && download.state != Download.STATE_DOWNLOADING) continue
                        if (download.stopReason != 0 && download.stopReason != STOP_REASON_AUTOMATIC_POLICY) continue
                        val reason = if (automaticAllowed && automaticEnabled()) 0 else STOP_REASON_AUTOMATIC_POLICY
                        if (download.stopReason != reason) downloadManager.setStopReason(download.request.id, reason)
                    }
                }
                _state.value = OfflineState(
                    items = (downloads.map(::toItem) + requested.values.map(::queuedItem)).sortedBy { it.track.title.lowercase() },
                    automaticEnabled = automaticEnabled(),
                    allowMetered = allowMetered(),
                    waitingForRequirements = downloadManager.isWaitingForRequirements,
                    automaticBytes = automaticUsage(downloads).storedBytes,
                    storageLimitBytes = storageLimitBytes(),
                )
                downloads.filter { it.request.id in completedIds }.forEach { download ->
                    val artwork = OfflineMetadata.decode(download.request.data)?.artworkUrl
                    if (artwork != null && artworkRequested.add(artwork)) scope.launch { OfflineArtworkStore.save(appContext, artwork) }
                }
            }
        }
    }

    private fun loadAll(): List<Download> = try {
        index.getDownloads().use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.download) }
        }
    } catch (_: Exception) {
        snapshot
    }

    /** Heal old ownership promotions that reset progress, only after verifying every local byte. */
    private fun repairCompletedProgress(download: Download): Download {
        if (download.contentLength <= 0 || download.getBytesDownloaded() >= download.contentLength || !isCompleteOnDisk(download)) return download
        val repaired = Download(download.request, download.state, download.startTimeMs, download.updateTimeMs,
            download.contentLength, download.stopReason, download.failureReason,
            DownloadProgress().apply { bytesDownloaded = download.contentLength; percentDownloaded = 100f })
        index.putDownload(repaired)
        return repaired
    }

    private fun toItem(download: Download): OfflineItem {
        val id = download.request.id
        val metadata = OfflineMetadata.decode(download.request.data)
        val bytes = download.getBytesDownloaded()
        return OfflineItem(
            id = id,
            track = metadata?.toTrack(id) ?: Track(id, "", "", 0L, null),
            automatic = metadata?.automatic == true,
            status = if (download.state == Download.STATE_COMPLETED && !isCompleteOnDisk(download)) OfflineStatus.FAILED else OfflineStatusMapper.status(download.state, downloadManager.isWaitingForRequirements),
            bytesDownloaded = bytes,
            contentLength = download.contentLength,
            percent = OfflineStatusMapper.percent(bytes, download.contentLength),
            neverAutomatic = isNeverAutomatic(id),
        )
    }

    private fun automaticUsage(downloads: List<Download>): AutomaticUsage {
        var stored = 0L
        var inFlight = 0L
        var pending = 0
        var tracked = 0
        for (download in downloads) {
            val metadata = OfflineMetadata.decode(download.request.data) ?: continue
            if (!metadata.automatic) continue
            tracked++
            when (download.state) {
                Download.STATE_COMPLETED -> stored += download.contentLength.coerceAtLeast(0L)
                Download.STATE_DOWNLOADING -> inFlight += download.getBytesDownloaded().coerceAtLeast(0L)
                Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_RESTARTING -> pending++
                else -> Unit
            }
        }
        return AutomaticUsage(stored, inFlight, pending, tracked)
    }

    /**
     * Keeps automatic content inside the user's budget.
     *
     * The limit is a ceiling on the bytes automatic content occupies, so it has to be enforced in
     * both directions: work that has not started is cancelled, and content that has already been
     * written is evicted. The least valuable automatic tracks go first, so the songs the user
     * listens to most are the ones that stay. Manual downloads are never touched.
     *
     * Each item is only removed once, and the loop stops as soon as it runs out of candidates, so
     * a budget that is already exceeded cannot spin.
     */
    private fun enforceAutomaticCap(limitBytes: Long) {
        val downloads = snapshot
        val removed = mutableSetOf<String>()

        // An automatic item larger than the whole budget can never fit.
        for (download in downloads) {
            val metadata = OfflineMetadata.decode(download.request.data)
            if (metadata?.automatic != true) continue
            if (download.state == Download.STATE_COMPLETED) continue
            if (download.contentLength > limitBytes) {
                DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, download.request.id, true)
                removed += download.request.id
            }
        }

        // Cancel automatic work that has not started, newest first, to make room.
        val pending = downloads
            .filter { download ->
                val metadata = OfflineMetadata.decode(download.request.data)
                metadata?.automatic == true && download.state in PENDING_STATES && download.request.id !in removed
            }
            .sortedByDescending { it.request.id }
        for (download in pending) {
            val usage = automaticUsage(downloads.filter { it.request.id !in removed })
            if (AutomaticDownloadPolicy.projectedBytes(usage, incomingBytes = 0) <= limitBytes) break
            DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, download.request.id, true)
            removed += download.request.id
        }

        // Evict completed automatic content, least valuable first, until the ceiling is met.
        val completed = downloads
            .filter { download ->
                val metadata = OfflineMetadata.decode(download.request.data)
                metadata?.automatic == true &&
                    download.state == Download.STATE_COMPLETED &&
                    download.request.id !in removed
            }
            .sortedBy { download ->
                // The oldest automatic content goes first.
                download.request.id
            }
        for (download in completed) {
            val usage = automaticUsage(downloads.filter { it.request.id !in removed })
            if (AutomaticDownloadPolicy.projectedBytes(usage, incomingBytes = 0) <= limitBytes) break
            DownloadService.sendRemoveDownload(appContext, OfflineDownloadService::class.java, download.request.id, true)
            removed += download.request.id
        }
    }

    /**
     * A restart may resolve a different rendition than the partial bytes on disk, which must never
     * be appended to. Drop every incomplete item's bytes up front so the download restarts cleanly;
     * completed items and manual downloads are left alone.
     */
    private fun recoverIncomplete() {
        try {
            index.getDownloads(*INCOMPLETE_STATES).use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { cache.removeResource(cursor.download.request.id) }
                }
            }
        } catch (_: Exception) {
            // A failed recovery only means a download may resume; pinning still prevents mixing.
        }
    }

    private fun automaticEnabled(): Boolean = prefs.getBoolean(KEY_AUTOMATIC, true)

    private fun isUnmeteredOnline(): Boolean {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        // Automatic saves may use mobile data only when the user allows it.
        return !connectivity.isActiveNetworkMetered || allowMetered()
    }

    companion object {
        private const val STOP_REASON_AUTOMATIC_POLICY = 1001
        private const val PREFS = "sidea-offline"
        private const val KEY_AUTOMATIC = "automatic-enabled"
        private const val KEY_ALLOW_METERED = "automatic-allow-metered"
        private const val KEY_NEVER_AUTOMATIC = "never-automatic"
        private const val KEY_LIMIT_BYTES = "automatic-limit-bytes"
        private const val KEY_PENDING = "pending-automatic"

        /** How long a play counts towards the frequent-play threshold. */
        const val RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        private const val MEDIA_DIR = "offline-media"

        /** Below this battery level an automatic save waits for a healthier battery. */
        const val LOW_BATTERY_PERCENT = 15

        /** The budget is never below 100 MiB or above 50 GiB, so a bad value cannot break the app. */
        const val MIN_LIMIT_BYTES = 100L * 1024L * 1024L
        const val MAX_LIMIT_BYTES = 50L * 1024L * 1024L * 1024L

        private val REQUIREMENTS = Requirements(
            Requirements.NETWORK or Requirements.NETWORK_UNMETERED or Requirements.DEVICE_STORAGE_NOT_LOW,
        )

        private val INCOMPLETE_STATES = intArrayOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_STOPPED,
            Download.STATE_RESTARTING,
            Download.STATE_REMOVING,
        )

        private val PENDING_STATES = setOf(
            Download.STATE_QUEUED,
            Download.STATE_STOPPED,
            Download.STATE_RESTARTING,
        )

        @Volatile
        private var instance: OfflineRepository? = null

        fun get(context: Context): OfflineRepository = instance ?: synchronized(this) {
            instance ?: OfflineRepository(context.applicationContext).also { instance = it }
        }

        private fun lastCountKey(id: String) = "play-count:$id"

        private fun suppressKey(id: String) = "suppress:$id"

        private fun sideaTrackUri(id: String): Uri = Uri.parse("${OfflineRoutePolicy.SCHEME}://$id")
    }
}
