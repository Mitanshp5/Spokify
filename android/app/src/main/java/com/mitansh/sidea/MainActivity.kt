package com.mitansh.sidea

import androidx.activity.compose.BackHandler
import com.mitansh.sidea.core.Connectivity
import com.mitansh.sidea.ui.LocalSongEnvironment
import com.mitansh.sidea.ui.SongEnvironment
import com.mitansh.sidea.ui.OfflineReadyScreen
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.mitansh.sidea.ui.LocalBottomContentPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mitansh.sidea.ui.DownloadUiState
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.library.LibraryRepository
import com.mitansh.sidea.data.library.PlaylistRepository
import com.mitansh.sidea.data.library.SideADatabase
import com.mitansh.sidea.data.youtube.YouTubeCatalog
import com.mitansh.sidea.playback.PlaybackService
import com.mitansh.sidea.ui.formatPlaybackTime
import com.mitansh.sidea.ui.HomeScreen
import com.mitansh.sidea.ui.SearchScreen
import com.mitansh.sidea.ui.LibraryScreen
import com.mitansh.sidea.ui.PlaylistCard
import com.mitansh.sidea.ui.PlaylistScreen
import com.mitansh.sidea.ui.CollectionScreen
import com.mitansh.sidea.ui.OnRepeatScreen
import com.mitansh.sidea.data.library.ListeningStatsRepository
import com.mitansh.sidea.ui.MotionPreference
import com.mitansh.sidea.ui.MotionAppFrame
import com.mitansh.sidea.ui.MotionMiniPlayer
import com.mitansh.sidea.ui.MotionScreens
import com.mitansh.sidea.ui.MotionDestination
import com.mitansh.sidea.ui.MiniPlayer
import com.mitansh.sidea.ui.NowPlayingScreen
import com.mitansh.sidea.ui.QueueEntry
import com.mitansh.sidea.ui.RadioSettingsScreen
import com.mitansh.sidea.ui.SpokifyNavigationBar
import com.mitansh.sidea.ui.SpokifyTab
import com.mitansh.sidea.ui.SpokifyTheme
import com.mitansh.sidea.ui.SpokifyInk
import com.mitansh.sidea.ui.DownloadsScreen
import com.mitansh.sidea.ui.ImportScreen
import com.mitansh.sidea.ui.SearchCoordinator
import com.mitansh.sidea.ui.SearchViewModel
import com.mitansh.sidea.ui.SearchViewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import com.mitansh.sidea.ui.SaveToPlaylistSheet

private const val POSITION_TICK_MS = 500L

private data class PlayerUiSnapshot(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<MediaItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
)

class MainActivity : ComponentActivity() {
    private var notificationOpen by mutableLongStateOf(0L)
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val catalog = YouTubeCatalog()
    private lateinit var library: LibraryRepository
    private lateinit var playlists: PlaylistRepository
    private lateinit var offline: com.mitansh.sidea.data.offline.OfflineRepository
    private lateinit var updates: com.mitansh.sidea.updates.AppUpdateViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_player", false) == true) notificationOpen++
        val db = SideADatabase.get(this)
        library = LibraryRepository(db.historyDao(), db.favoritesDao())
        playlists = PlaylistRepository(db.playlistDao())
        offline = com.mitansh.sidea.data.offline.OfflineRepository.get(this)
        val searchViewModel = ViewModelProvider(this, SearchViewModelFactory(catalog, com.mitansh.sidea.ui.SearchHistory.persistent(applicationContext)))[SearchViewModel::class.java]
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().apply {
            val future = this
            addListener({
                if (!isDestroyed && !future.isCancelled) {
                    try { controller = future.get() } catch (_: Exception) { }
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
        updates = ViewModelProvider(this)[com.mitansh.sidea.updates.AppUpdateViewModel::class.java]
        setContent { SpokifyApp(library, playlists, searchViewModel.searches, controller, offline, catalog, applicationContext, notificationOpen, updates) }
    }

    override fun onStart() {
        super.onStart()
        updates.checkDaily()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_player", false)) notificationOpen++
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun SpokifyApp(
    library: LibraryRepository,
    playlists: PlaylistRepository,
    searches: SearchCoordinator,
    controller: MediaController?,
    offline: com.mitansh.sidea.data.offline.OfflineRepository,
    catalog: YouTubeCatalog,
    context: android.content.Context,
    notificationOpen: Long,
    updates: com.mitansh.sidea.updates.AppUpdateViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(SpokifyTab.HOME) }
    var section by rememberSaveable { mutableStateOf<String?>(if (Connectivity.isOnline(context)) null else "offline") }
    var sectionParent by rememberSaveable { mutableStateOf<String?>(null) }
    fun backSection() { section = sectionParent; sectionParent = null }
    fun settingsChild(target: String) { sectionParent = "settings"; section = target }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var renameName by rememberSaveable { mutableStateOf("") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    var showCreate by rememberSaveable { mutableStateOf(false) }

    val search by searches.state.collectAsStateWithLifecycle()
    var playerSnapshot by remember(controller) { mutableStateOf(controller.toUiSnapshot()) }
    var handledNotificationOpen by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(notificationOpen, playerSnapshot.current?.uid) {
        if (notificationOpen > handledNotificationOpen && playerSnapshot.current != null) {
            expanded = true
            handledNotificationOpen = notificationOpen
        }
    }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val history by remember(library) { library.observeHistory() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by remember(library) { library.observeFavorites() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val listeningStats = remember(context) { ListeningStatsRepository(SideADatabase.get(context).listeningStatsDao()) }
    val monthly by remember(listeningStats) { listeningStats.observeMonthly() }.collectAsStateWithLifecycle(initialValue = com.mitansh.sidea.data.library.MonthlyListening())
    val onRepeat by remember(listeningStats) { listeningStats.observeOnRepeat() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistRows by remember(playlists) { playlists.observePlaylists() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistSummaries by remember(playlists) { playlists.observeSummaries() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistCounts = remember(playlistSummaries) { playlistSummaries.associate { it.playlistId to it.songCount } }
    val playlistArtwork = remember(playlistSummaries) { playlistSummaries.associate { it.playlistId to it.artworkUrl } }
    val memberships by remember(playlists) { playlists.observeMemberships() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlistCards = remember(playlistRows, playlistCounts, playlistArtwork) {
        playlistRows.map { PlaylistCard(it.id, it.name, playlistCounts[it.id] ?: 0, playlistArtwork[it.id], it.createdAtEpochMs) }
    }
    val savedUids = remember(favorites, memberships) { favorites.map { it.uid }.toSet() + memberships.map { it.uid } }
    val savingSongs = remember { mutableStateMapOf<String, Boolean>() }
    val snackbar = remember { SnackbarHostState() }
    val playlistEntries by remember(playlists, selectedPlaylist) {
        selectedPlaylist?.let(playlists::observeEntries) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloads = remember(offline) { DownloadUiState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(offline, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { offline.state.collect(downloads::update) }
    }
    val completedDownloads = downloads.completedItems
    val online by remember(context) { Connectivity.observe(context) }.collectAsState(initial = Connectivity.isOnline(context))
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun manageSongSave(track: Track) {
        focusManager.clearFocus()
        keyboard?.hide()
        addTrack = track
    }
    fun saveSong(track: Track) {
        if (track.uid in savingSongs) return
        if (track.uid in savedUids) {
            manageSongSave(track)
            return
        }
        savingSongs[track.uid] = true
        scope.launch {
            val saved = try {
                library.addFavorite(track)
                true
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { false
            } finally { savingSongs.remove(track.uid) }
            snackbar.currentSnackbarData?.dismiss()
            if (saved) {
                if (snackbar.showSnackbar("Added to Liked Songs", actionLabel = "Change", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    manageSongSave(track)
                }
            } else snackbar.showSnackbar("Couldn't save song. Try again.")
        }
    }
    val saveAction by rememberUpdatedState<(Track) -> Unit>(::saveSong)
    val manageSaveAction by rememberUpdatedState<(Track) -> Unit>(::manageSongSave)
    val savingUids = savingSongs.keys.toSet()
    val songEnvironment = remember(playerSnapshot.current?.uid, downloads, offline, savedUids, savingUids) {
        SongEnvironment(playerSnapshot.current?.uid, downloads, offline::remove, offline::retry,
            savedUids, savingUids, { saveAction(it) }, { manageSaveAction(it) })
    }

    fun selectSong(tracks: List<Track>, track: Track) {
        focusManager.clearFocus()
        keyboard?.hide()
        if (controller?.currentMediaItem?.mediaId == track.id && controller.mediaItemCount > 0) {
            expanded = true
        } else {
            val queueTitle = when (section) {
                "on-repeat" -> "On Repeat"
                "playlist" -> playlistRows.firstOrNull { it.id == selectedPlaylist }?.name ?: "Playlist"
                "favorites" -> "Liked Songs"
                "history" -> "Recently played"
                "offline" -> "Offline Backup"
                "capsule", "top-songs" -> "Your top songs"
                else -> if (tab == SpokifyTab.SEARCH) "Search" else "Your music"
            }
            playList(controller, tracks, track, queueTitle)
        }
    }
    LaunchedEffect(online) {
        if (!online && tab == SpokifyTab.HOME && section == null) section = "offline"
    }
    val radioPreferences = remember { com.mitansh.sidea.data.radio.RadioPreferences.of(context) }
    var autoplayEnabled by remember { mutableStateOf(radioPreferences.autoplayEnabled) }
    var hiddenCount by remember { mutableStateOf(radioPreferences.hiddenTracks().size) }
    var importLoading by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<String?>(null) }
    var importSession by remember { mutableStateOf<com.mitansh.sidea.data.imports.ImportSessionStore.Session?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importSaved by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val importCoordinator = remember {
        com.mitansh.sidea.data.imports.ImportCoordinator(
            context = context,
            playlists = playlists,
            provenance = SideADatabase.get(context).importProvenanceDao(),
            search = { query -> catalog.search(query) },
        )
    }

    /** Hides a song from the radio, so it is never suggested again. */
    fun hideFromRadio(track: Track) {
        radioPreferences.hide(track.id)
        hiddenCount = radioPreferences.hiddenTracks().size
    }

    /** Reads a playlist from a link and matches each row to a playable recording. */
    suspend fun runImport(link: String) {
        if (importLoading) return
        importLoading = true
        importSession = null
        importError = null
        importSaved = null
        importProgress = "Reading the playlist…"
        try {
            val result = importCoordinator.read(link) { message ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { importProgress = message }
            }
            importSession = result.session.takeIf { it.items.isNotEmpty() }
            importError = result.error ?: "No songs were available in this playlist.".takeIf { result.session.items.isEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            importError = "The playlist could not be read. Please try again."
        } finally {
            importLoading = false
            importProgress = null
        }
    }

    /** Saves the reviewed import. Committing twice updates the same playlist. */
    suspend fun saveImport(selections: Map<Int, Track>) {
        if (importLoading) return
        val session = importSession ?: return
        importLoading = true
        importError = null
        try {
            val result = importCoordinator.commit(session.id, selections)
            if (result.error != null) {
                importError = result.error
            } else {
                importSaved = if (result.alreadyCommitted) {
                    "Already saved as a playlist."
                } else {
                    "Saved ${result.saved} songs" + if (result.unmatched > 0) " · ${result.unmatched} need a check" else ""
                }
                importSession = null
                section = null
                tab = SpokifyTab.LIBRARY
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            importError = "The playlist could not be saved. Please try again."
        } finally {
            importLoading = false
        }
    }

    fun leaveImport() {
        importJob?.cancel()
        importJob = null
        backSection()
    }

    BackHandler(enabled = section != null || expanded) {
        if (expanded) expanded = false
        else if (section == "import") leaveImport()
        else backSection()
    }

    DisposableEffect(controller) {
        val listener = object : androidx.media3.common.Player.Listener {
            private fun update() {
                playerSnapshot = controller.toUiSnapshot()
            }
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) = update()
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                playerSnapshot = controller.toUiSnapshot()
            }
        }
        controller?.addListener(listener)
        playerSnapshot = controller.toUiSnapshot()
        onDispose { controller?.removeListener(listener) }
    }

    CompositionLocalProvider(LocalSongEnvironment provides songEnvironment) {
    SpokifyTheme {
        MotionPreference {
        com.mitansh.sidea.ui.ProfileNavigation(drawerState,
            onRecents = { sectionParent = null; section = "history" }, onCapsule = { sectionParent = null; section = "capsule" },
            onSettings = { sectionParent = null; section = "settings" }, updates = updates,
            onUpdates = { sectionParent = null; section = "updates" }) {
        Box(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
            var chromeHeight by remember { mutableStateOf(160.dp) }
        MotionAppFrame(expanded = expanded && playerSnapshot.current != null, onDismissPlayer = { expanded = false }, player = {
            playerSnapshot.current?.let { currentTrack ->
            PlayerPosition(controller, playerSnapshot.positionMs) { position ->
            NowPlayingScreen(
                title = currentTrack.title,
                artist = currentTrack.artist,
                artworkUrl = currentTrack.artworkUrl,
                queueTitle = playerSnapshot.queue.getOrNull(playerSnapshot.currentIndex)?.mediaMetadata?.extras?.getString("queue_title") ?: "Your music",
                isPlaying = playerSnapshot.isPlaying,
                isBuffering = playerSnapshot.isBuffering,
                isFavorite = favorites.any { it.uid == currentTrack.uid },
                positionMs = position,
                durationMs = playerSnapshot.durationMs,
                queueSize = playerSnapshot.queue.size,
                queue = playerSnapshot.queue.mapIndexed { index, item ->
                    QueueEntry(index, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
                },
                currentQueueIndex = playerSnapshot.currentIndex,
                shuffleEnabled = playerSnapshot.shuffleEnabled,
                repeatMode = playerSnapshot.repeatMode,
                onSeek = { controller?.seekTo(it) },
                onPlayPause = { togglePlayback(controller) },
                onNext = { controller?.seekToNextMediaItem() },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onToggleFavorite = { saveSong(currentTrack) },
                onDownload = { offline.enqueue(currentTrack) },
                onToggleShuffle = { controller?.shuffleModeEnabled = !playerSnapshot.shuffleEnabled },
                onCycleRepeat = {
                    controller?.repeatMode = when (playerSnapshot.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                },
                onShowQueue = { },
                onSelectQueueEntry = { index -> if (index != playerSnapshot.currentIndex) controller?.seekTo(index, 0L) },
                onClose = { expanded = false },
                trackId = currentTrack.id,
                onAddToPlaylist = { manageSongSave(currentTrack) },
                error = playerSnapshot.error,
                onRetry = { controller?.let { it.prepare(); it.play() } },
            )
            }
            }
        }, chrome = {
            Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            if (!keyboardVisible) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Black.copy(alpha = .65f), 1f to Color.Black))
                .navigationBarsPadding().onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }) {
            MotionMiniPlayer {
            playerSnapshot.current?.let { track ->
                PlayerPosition(controller, playerSnapshot.positionMs) { position ->
                MiniPlayer(
                    song = track,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    isPlaying = playerSnapshot.isPlaying,
                    progress = if (playerSnapshot.durationMs > 0) {
                        position.toFloat() / playerSnapshot.durationMs
                    } else {
                        0f
                    },
                    onPlayPause = { togglePlayback(controller) },
                    onOpen = { expanded = true },
                )
                }
            }

            }
            SpokifyNavigationBar(current = tab, onSelect = { sectionParent = null; tab = it; section = if (it == SpokifyTab.HOME && !online) "offline" else null }, onCreate = { showCreate = true })
            if (!online) Box(Modifier.fillMaxWidth().background(Color.Black).heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
                Text("You're offline", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(6.dp))
            }
            }
            }
        }) {
        Box(Modifier.fillMaxSize().background(SpokifyInk).statusBarsPadding().imePadding()) {
            CompositionLocalProvider(LocalBottomContentPadding provides if (keyboardVisible) 0.dp else chromeHeight) {
            MotionScreens(MotionDestination(tab, section, if (section == "playlist") selectedPlaylist else null), Modifier.fillMaxSize()) { destination ->
                when (destination.section) {
                    "settings" -> com.mitansh.sidea.ui.SettingsScreen(
                        onBack = ::backSection, onPlayback = { settingsChild("radio") }, onDownloads = { settingsChild("downloads") },
                        onOffline = { settingsChild("offline") }, onImport = { settingsChild("import") }, onUpdates = { settingsChild("updates") }, historyCount = search.recent.size, onClearHistory = searches::clearHistory)
                    "updates" -> com.mitansh.sidea.ui.AppUpdatesScreen(updates, ::backSection)
                    "capsule", "top-songs" -> com.mitansh.sidea.ui.SoundCapsuleScreen(monthly, favorites,
                        onBack = { section = if (destination.section == "top-songs") "capsule" else null },
                        onPlay = { selectSong(monthly.songs.map { it.track }, it) }, onFavorite = ::saveSong,
                        onDownload = { offline.enqueue(it) }, topSongs = destination.section == "top-songs", onOpenTopSongs = { section = "top-songs" })
                    "offline" -> OfflineReadyScreen(
                        items = completedDownloads,
                        favorites = favorites,
                        onPlay = { track -> selectSong(completedDownloads.map { it.track }, track) },
                        onPlayAll = { tracks -> if (tracks.isNotEmpty()) playList(controller, tracks, 0, "Offline Backup") },
                        onFavorite = ::saveSong,
                        onRemove = offline::remove,
                        onBack = ::backSection,
                        onSettings = { section = "downloads" },
                        offline = !online,
                    )
                    "on-repeat" -> OnRepeatScreen(
                        songs = onRepeat, favorites = favorites, onBack = ::backSection,
                        onPlay = { selectSong(onRepeat, it) },
                        onPlayAll = { if (onRepeat.isNotEmpty()) { controller?.shuffleModeEnabled = false; playList(controller, onRepeat, 0, "On Repeat") } },
                        onShuffle = { if (onRepeat.isNotEmpty()) { controller?.shuffleModeEnabled = true; playList(controller, onRepeat, onRepeat.indices.random(), "On Repeat") } },
                        onFavorite = ::saveSong,
                        onDownload = { offline.enqueue(it) }, onDownloadAll = { offline.enqueueAll(onRepeat) },
                        onAddToPlaylist = ::manageSongSave,
                    )
                    "playlist" -> PlaylistScreen(
                        name = playlistRows.firstOrNull { it.id == destination.playlistId }?.name ?: "Playlist",
                        entries = playlistEntries,
                        favorites = favorites,
                        onBack = ::backSection,
                        onPlay = { selectSong(playlistEntries.map { it.track }, it) },
                        onFavorite = ::saveSong,
                        onDownload = { offline.enqueue(it) },
                        onRemove = { entry -> scope.launch { playlists.removeEntry(entry.entryId) } },
                        onMove = { entry, up -> scope.launch { playlists.moveEntry(entry.playlistId, entry.entryId, if (up) entry.position - 1 else entry.position + 1) } },
                        onDownloadAll = { tracks -> offline.enqueueAll(tracks) },
                        onPlayAll = { if (playlistEntries.isNotEmpty()) playList(controller, playlistEntries.map { it.track }, 0, playlistRows.firstOrNull { it.id == selectedPlaylist }?.name ?: "Playlist") },
                        onRename = { renameName = playlistRows.firstOrNull { it.id == selectedPlaylist }?.name.orEmpty(); dialog = "rename" },
                        onDelete = { dialog = "delete" },
                    )

                    "favorites" -> CollectionScreen(
                        title = "Liked Songs",
                        onBack = ::backSection,
                        songs = favorites,
                        favorites = favorites,
                        onPlay = { selectSong(favorites, it) },
                        onFavorite = ::saveSong,
                        onDownload = { offline.enqueue(it) },
                    )

                    "history" -> CollectionScreen(
                        title = "Recently Played",
                        onBack = ::backSection,
                        songs = history,
                        favorites = favorites,
                        onPlay = { selectSong(listOf(it), it) },
                        onFavorite = ::saveSong,
                        onDownload = { offline.enqueue(it) },
                    )

                    "downloads" -> {
                        val offlineState by offline.state.collectAsStateWithLifecycle()
                        DownloadsScreen(
                        state = offlineState,
                        limitBytes = offlineState.storageLimitBytes,
                        onSetLimit = { offline.setStorageLimitBytes(it) },
                        onAutomaticEnabled = { offline.setAutomaticEnabled(it) },
                        onAllowMetered = { offline.setAllowMetered(it) },
                        onRemove = { offline.remove(it) },
                        onRetry = { id -> offlineState.items.firstOrNull { it.id == id }?.let { offline.retry(it.track) } },
                        onKeep = { offline.promoteToManual(it) },
                        onNeverAutomatic = { id, never -> offline.setNeverAutomatic(id, never) },
                        onBack = ::backSection,
                    )
                    }

                    "radio" -> RadioSettingsScreen(
                        autoplayEnabled = autoplayEnabled,
                        hiddenCount = hiddenCount,
                        onAutoplayEnabled = { enabled ->
                            radioPreferences.autoplayEnabled = enabled
                            autoplayEnabled = enabled
                        },
                        onReset = {
                            radioPreferences.reset()
                            autoplayEnabled = radioPreferences.autoplayEnabled
                            hiddenCount = radioPreferences.hiddenTracks().size
                        },
                        onBack = ::backSection,
                    )
                    "import" -> ImportScreen(
                        loading = importLoading,
                        progress = importProgress,
                        session = importSession,
                        error = importError,
                        savedMessage = importSaved,
                        onFetch = { link -> if (!importLoading) importJob = scope.launch { runImport(link) } },
                        onSave = { selections -> scope.launch { saveImport(selections) } },
                        onCancel = { leaveImport(); importSession = null; importError = null },
                        onBack = { leaveImport() },
                    )

                    else -> when (destination.tab) {
                        SpokifyTab.HOME -> HomeScreen(
                            greeting = greeting(),
                            recentlyPlayed = history.take(12),
                            favorites = favorites,
                            offlineCount = completedDownloads.size,
                            onPlay = { selectSong(listOf(it), it) },
                            onFavorite = ::saveSong,
                            onDownload = { offline.enqueue(it) },
                            onAddToPlaylist = ::manageSongSave,
                            onHide = { hideFromRadio(it) },
                            onOpenFavorites = { section = "favorites" },
                            onOpenDownloads = { section = "offline" },
                            onOpenProfile = { scope.launch { drawerState.open() } },
                            onOpenSearch = { tab = SpokifyTab.SEARCH },
                            onRepeatCount = onRepeat.size,
                            onOpenOnRepeat = { section = "on-repeat" },
                            playlists = playlistCards,
                            onOpenPlaylist = { card -> selectedPlaylist = card.id; section = "playlist" },
                        )

                        SpokifyTab.SEARCH -> SearchScreen(
                            query = search.query,
                            onQueryChange = searches::updateQuery,
                            loading = search.loading,
                            error = search.error,
                            results = search.results,
                            favorites = favorites,
                            onSearch = searches::search,
                            recent = search.recent, onRemoveRecent = searches::removeRecent, onClearHistory = searches::clearHistory,
                            onOpenProfile = { scope.launch { drawerState.open() } }, onOpenOnRepeat = { section = "on-repeat" }, browseTracks = history,
                            onPlay = { searches.recordSelection(it); selectSong(listOf(it), it) },
                            onFavorite = ::saveSong,
                            onDownload = { offline.enqueue(it) },
                            onAddToPlaylist = ::manageSongSave,
                            onHide = { hideFromRadio(it) },
                        )
                        SpokifyTab.LIBRARY -> LibraryScreen(
                            playlists = playlistCards,
                            favoriteCount = favorites.size,
                            historyCount = history.size,
                            downloadCount = completedDownloads.size,
                            onOpenPlaylist = { card -> selectedPlaylist = card.id; section = "playlist" },
                            onOpenFavorites = { section = "favorites" },
                            onOpenHistory = { section = "history" },
                            onOpenDownloads = { section = "downloads" },
                            onOpenOfflineReady = { section = "offline" },
                            onOpenImport = { section = "import" },
                            onOpenSettings = { section = "settings" },
                            onOpenProfile = { scope.launch { drawerState.open() } },
                            onCreatePlaylist = { playlistName = ""; dialog = "create" },
                            onRepeatCount = onRepeat.size,
                            onOpenOnRepeat = { section = "on-repeat" },
                        )
                    }
                }
            }

            }

        }

        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = if (expanded) 24.dp else 148.dp))
        com.mitansh.sidea.ui.CreateMenu(showCreate, tab, onDismiss = { showCreate = false },
            onSelect = { showCreate = false; sectionParent = null; tab = it; section = null },
            onPlaylist = { showCreate = false; playlistName = ""; dialog = "create" },
            onImport = { showCreate = false; sectionParent = null; section = "import" })
        }
        }
    }

    if (dialog != null) {
        SpokifyTheme {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(when (dialog) { "create" -> "New playlist"; "rename" -> "Rename playlist"; else -> "Delete playlist?" }) },
                text = {
                    if (dialog == "create" || dialog == "rename") {
                        OutlinedTextField(
                            if (dialog == "rename") renameName else playlistName,
                            { if (dialog == "rename") renameName = it else playlistName = it },
                            label = { Text("Name") },
                            singleLine = true,
                        )
                    } else {
                        Text("This permanently removes the playlist and its songs.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // The values are read here, before the coroutine starts, so a later change
                        // to the dialog state cannot change what is saved.
                        val action = dialog
                        val id = selectedPlaylist
                        val newName = if (action == "rename") renameName.trim() else playlistName.trim()
                        scope.launch {
                            when (action) {
                                "create" -> if (newName.isNotBlank()) playlists.create(newName)
                                "rename" -> if (id != null && newName.isNotBlank()) playlists.rename(id, newName)
                                "delete" -> if (id != null) playlists.deletePlaylist(id)
                            }
                        }
                        if (action == "delete") { selectedPlaylist = null; section = null }
                        dialog = null
                    }) { Text(if (dialog == "delete") "Delete" else "Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }

    }
    addTrack?.let { track ->
        SpokifyTheme {
            MotionPreference {
            key(track.uid) {
            SaveToPlaylistSheet(
                track = track, liked = favorites.any { it.uid == track.uid },
                memberships = memberships.filter { it.uid == track.uid }.map { it.playlistId }.toSet(),
                playlists = playlistRows, counts = playlistCounts, artwork = playlistArtwork,
                onCommit = { initialLiked, liked, initialIds, selectedIds ->
                    SideADatabase.get(context).withTransaction {
                        if (initialLiked != liked) library.setFavorite(track, liked)
                        playlists.updateMemberships(track, initialIds, selectedIds)
                    }
                },
                onCreate = { name -> playlists.create(name, listOf(track)) },
                onDismiss = { addTrack = null },
            )
            }
            }
        }
    }
    }
}

@Composable
private fun PlayerPosition(controller: MediaController?, initial: Long, content: @Composable (Long) -> Unit) {
    var position by remember(controller) { mutableLongStateOf(initial) }
    LaunchedEffect(controller) {
        while (true) {
            position = controller?.currentPosition ?: initial
            kotlinx.coroutines.delay(POSITION_TICK_MS)
        }
    }
    content(position)
}

private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun playList(controller: MediaController?, tracks: List<Track>, track: Track, queueTitle: String) = playList(controller, tracks, tracks.indexOf(track).coerceAtLeast(0), queueTitle)

private fun playList(controller: MediaController?, tracks: List<Track>, startIndex: Int, queueTitle: String) {
    val extras = Bundle().apply { putString("queue_title", queueTitle) }
    val items = tracks.map { track -> MediaItem.Builder().setMediaId(track.id).setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setArtworkUri(track.artworkUrl?.let(android.net.Uri::parse)).setDurationMs(track.durationMs).setExtras(extras).build()).build() }
    if (items.isEmpty()) return
    controller?.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
    controller?.prepare(); controller?.play()
}

private fun MediaItem.asTrack() = Track(mediaId, mediaMetadata.title?.toString().orEmpty(), mediaMetadata.artist?.toString().orEmpty(), mediaMetadata.durationMs ?: 0L, mediaMetadata.artworkUri?.toString())

private fun togglePlayback(player: MediaController?) {
    player ?: return
    if (player.playerError != null) { player.prepare(); player.play() }
    else if (player.playWhenReady) player.pause() else player.play()
}

private fun MediaController?.toUiSnapshot(): PlayerUiSnapshot {
    val player = this ?: return PlayerUiSnapshot()
    val queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    return PlayerUiSnapshot(
        current = player.currentMediaItem?.asTrack(),
        isPlaying = player.isPlaying,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        error = player.playerError?.let(com.mitansh.sidea.playback.PlaybackFailurePolicy::message),
        positionMs = player.currentPosition,
        durationMs = player.duration.coerceAtLeast(0L),
        queue = queue,
        currentIndex = player.currentMediaItemIndex,
        repeatMode = player.repeatMode,
        shuffleEnabled = player.shuffleModeEnabled,
    )
}

@Composable
private fun PlayerButton(description: String, icon: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(painterResource(icon), contentDescription = description, tint = com.mitansh.sidea.ui.SpokifyGreen) }
}
