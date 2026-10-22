package com.mitansh.sidea.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mitansh.sidea.BuildConfig
import com.mitansh.sidea.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mitansh.sidea.updates.AppUpdateViewModel

val LocalProfileName = compositionLocalOf { "Your music" }
val LocalProfilePhoto = compositionLocalOf<java.io.File?> { null }
private val LocalAvailableUpdate = compositionLocalOf<StateFlow<Boolean>?> { null }

@Composable
private fun ProfileAvatar(name: String, photo: java.io.File?, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(Color(0xFFB6D5C3)), contentAlignment = Alignment.Center) {
        val trimmed = name.trim()
        val initial = if (trimmed.isEmpty()) "?" else trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
        Text(initial, color = Color(0xFF17352A), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (photo != null) AsyncImage(photo, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ProfileButton(onClick: () -> Unit, modifier: Modifier = Modifier.size(34.dp)) {
    val available = LocalAvailableUpdate.current?.collectAsStateWithLifecycle()?.value == true
    Box(modifier.semantics { contentDescription = if (available) "Open profile, update available" else "Open profile" }.motionClickable(onClick = onClick)) {
        ProfileAvatar(LocalProfileName.current, LocalProfilePhoto.current, Modifier.matchParentSize())
        if (available) Box(Modifier.align(Alignment.TopEnd).size(10.dp)
            .background(Color(0xFFFFD54F), CircleShape).border(1.5.dp, SpokifyInk, CircleShape))
    }
}

@Composable
fun ProfileNavigation(drawerState: DrawerState, onRecents: () -> Unit, onCapsule: () -> Unit, onSettings: () -> Unit,
    updates: AppUpdateViewModel, onUpdates: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("local-profile", android.content.Context.MODE_PRIVATE) }
    val photos = remember(context) { ProfilePhotoStore(context.applicationContext) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("name", "Your music") ?: "Your music") }
    var photo by rememberSaveable { mutableStateOf(prefs.getString("photo", null)) }
    var onboarding by rememberSaveable { mutableStateOf(!prefs.getBoolean("onboarding-complete", !prefs.getString("name", null).isNullOrBlank())) }
    var onboardingName by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(name) }
    var draftPhoto by rememberSaveable { mutableStateOf(photo) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun editProfile() { draft = name; draftPhoto = photo; photoError = null; editing = true }
    fun cancelEdit() {
        if (photoBusy) return
        val discarded = draftPhoto.takeIf { it != photo }
        editing = false
        scope.launch { photos.remove(discarded) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photoBusy = true; photoError = null
            try {
                val imported = photos.import(uri)
                val discarded = draftPhoto.takeIf { it != photo }
                draftPhoto = imported
                photos.remove(discarded)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
            } catch (failure: Exception) { photoError = failure.message ?: "Couldn't save photo. Try again."
            } finally { photoBusy = false }
        }
    }
    fun navigate(action: () -> Unit) { scope.launch { drawerState.close(); action() } }
    CompositionLocalProvider(LocalProfileName provides name, LocalProfilePhoto provides photos.file(photo), LocalAvailableUpdate provides updates.available) {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF1F1F1F), drawerShape = RectangleShape, modifier = Modifier.fillMaxWidth(.9f)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    Surface(color = Color(0xFF2A2A2A), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                        .motionClickable { editProfile() }) {
                        Row(Modifier.heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProfileButton({ editProfile() }, Modifier.size(54.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(name, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Text("Edit local profile", color = SpokifyMuted, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    DrawerDestination("Your Sound Capsule", R.drawable.ic_stats) { navigate(onCapsule) }
                    DrawerDestination("Recents", R.drawable.ic_history) { navigate(onRecents) }
                    DrawerDestination("Settings and privacy", R.drawable.ic_settings) { navigate(onSettings) }
                    UpdateDrawerDestination(updates) { navigate(onUpdates) }
                    Spacer(Modifier.weight(1f))
                    Text("Spokify · Your music stays with you", color = SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }, content = content)
        BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
        if (editing) AlertDialog(onDismissRequest = ::cancelEdit, title = { Text("Your profile") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileAvatar(draft, photos.file(draftPhoto), Modifier.size(88.dp))
                    TextButton(enabled = !photoBusy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                    if (draftPhoto != null) TextButton(enabled = !photoBusy, onClick = {
                        val discarded = draftPhoto.takeIf { it != photo }; draftPhoto = null
                        scope.launch { photos.remove(discarded) }
                    }) { Text("Use initial instead") }
                    if (photoBusy) CircularProgressIndicator(Modifier.size(22.dp))
                    photoError?.let { Text(it, color = SpokifyError, fontSize = 13.sp) }
                    OutlinedTextField(draft, { draft = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = draft.isNotBlank() && !photoBusy, onClick = {
                val oldPhoto = photo.takeIf { it != draftPhoto }
                name = draft.trim(); photo = draftPhoto
                prefs.edit().putString("name", name).putString("photo", photo).putBoolean("onboarding-complete", true).apply()
                editing = false
                scope.launch { photos.remove(oldPhoto) }
            }) { Text("Save") } },
            dismissButton = { TextButton(enabled = !photoBusy, onClick = ::cancelEdit) { Text("Cancel") } })
        if (onboarding) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Column(Modifier.fillMaxSize().background(SpokifyInk).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text("Welcome to Spokify", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("What should we call you?", color = SpokifyMuted, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
                OutlinedTextField(onboardingName, { onboardingName = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = onboardingName.isNotBlank(), onClick = {
                    name = onboardingName.trim()
                    prefs.edit().putString("name", name).putBoolean("onboarding-complete", true).apply()
                    onboarding = false
                }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp), colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) { Text("Continue", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun UpdateDrawerDestination(updates: AppUpdateViewModel, onClick: () -> Unit) {
    val available by updates.available.collectAsStateWithLifecycle()
    DrawerDestination(if (available) "Update Spokify" else "App updates", R.drawable.ic_download, onClick)
}

@Composable
private fun DrawerDestination(title: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).motionClickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(28.dp), tint = Color.White)
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
fun SettingsRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(28.dp), tint = Color.White)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onPlayback: () -> Unit, onDownloads: () -> Unit, onOffline: () -> Unit,
    onImport: () -> Unit, onUpdates: () -> Unit, historyCount: Int, onClearHistory: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    BackHandler(searching) { searching = false; filter = "" }
    Column(Modifier.fillMaxSize().background(SpokifyInk)) {
        Row(Modifier.background(Color(0xFF272727)).fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Go back", tint = Color.White) }
            Text("Settings", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            MotionIconButton(onClick = { searching = !searching; filter = "" }) { Icon(painterResource(R.drawable.ic_search), "Search settings", tint = Color.White) }
        }
        if (searching) TextField(filter, { filter = it }, label = { Text("Search settings") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomContentPadding.current)) {
            if (filter.isBlank()) item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple("Offline Backup", R.drawable.ic_downloaded, onOffline), Triple("Playback", R.drawable.ic_play, onPlayback),
                        Triple("Downloads", R.drawable.ic_download, onDownloads)).forEach { (label, icon, action) ->
                        Surface(color = Color(0xFF292929), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(92.dp).motionClickable(onClick = action)) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(painterResource(icon), null, Modifier.size(26.dp), tint = Color.White)
                                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                                Text(if (label == "Playback") "Preferences" else if (label == "Downloads") "Storage" else "Your music", color = SpokifyMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            if ("Playback Autoplay Quiet notifications".contains(filter, true)) item { SettingsRow("Playback", "Autoplay · Quiet notifications", R.drawable.ic_play, onPlayback) }
            if ("Notifications".contains(filter, true)) item { SettingsRow("Notifications", "Playback controls and notification permissions", R.drawable.ic_bell) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } }
            if ("Apps Import playlist".contains(filter, true)) item { SettingsRow("Import from other apps", "Public Spotify and YouTube playlist links", R.drawable.ic_add, onImport) }
            if ("Data-saving offline downloads storage media quality".contains(filter, true)) item { SettingsRow("Data-saving and offline", "Downloads · Storage · Network preferences", R.drawable.ic_download, onDownloads) }
            if ("Privacy search history".contains(filter, true)) item { SettingsRow("Search history", "$historyCount recent searches · Clear history", R.drawable.ic_history) { clear = true } }
            if ("App updates version download install".contains(filter, true)) item { SettingsRow("App updates", "Check for updates · Version ${BuildConfig.VERSION_NAME}", R.drawable.ic_download, onUpdates) }
            if ("About support version".contains(filter, true)) item { SettingsRow("About Spokify", "Version ${BuildConfig.VERSION_NAME}", R.drawable.ic_info) { about = true } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear recent searches?") }, text = { Text("This removes your recent search queries and selections from this device. Your music library is kept.") }, confirmButton = { TextButton(onClick = { onClearHistory(); clear = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Spokify ${BuildConfig.VERSION_NAME}") }, text = { Text("Your library, listening records and search history are stored on this device. Searches, artwork, lyrics and playback contact their source providers.") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Done") } })
}
