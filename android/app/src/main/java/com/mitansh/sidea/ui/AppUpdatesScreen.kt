package com.mitansh.sidea.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mitansh.sidea.BuildConfig
import com.mitansh.sidea.updates.AppUpdateViewModel
import com.mitansh.sidea.updates.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AppUpdatesScreen(updates: AppUpdateViewModel, onBack: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installBusy by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    fun install() {
        scope.launch {
            installBusy = true
            installError = null
            try { context.startActivity(updates.installIntent()) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { installError = failure.message ?: "Couldn't open the installer. Try again." }
            finally { installBusy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else installError = "Allow updates from Spokify in Android settings, then tap Install update."
    }
    LaunchedEffect(Unit) { updates.checkDaily() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("App updates", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp + LocalBottomContentPadding.current)) {
            Text("Spokify", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
            Text("Installed version ${BuildConfig.VERSION_NAME}", color = SpokifyMuted, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
            Text(when (state.phase) {
                UpdatePhase.IDLE -> "Check for updates"
                UpdatePhase.CHECKING -> "Checking for updates…"
                UpdatePhase.NO_RELEASE -> "No published update yet"
                UpdatePhase.CURRENT -> "You're up to date"
                UpdatePhase.AVAILABLE -> "Version ${state.update?.manifest?.versionName} is available"
                UpdatePhase.DOWNLOADING -> "Downloading update…"
                UpdatePhase.READY -> "Your update is ready"
                UpdatePhase.ERROR -> "Couldn't finish the update"
            }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Download future updates here, then confirm installation in Android. Your library stays on this device.", color = SpokifyMuted,
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
            Text("Automatic checks run on your first app open each day. You can check again whenever you want.", color = SpokifyMuted,
                fontSize = 13.sp, modifier = Modifier.padding(bottom = 20.dp))
            if (state.phase == UpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.update?.let { update ->
                val size = String.format(Locale.US, "%.1f MB", update.manifest.sizeBytes / 1_048_576.0)
                Text("Spokify ${update.manifest.versionName} · $size", color = SpokifyMuted, modifier = Modifier.padding(bottom = 12.dp))
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (state.received.toFloat() / update.manifest.sizeBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.received * 100 / update.manifest.sizeBytes).coerceIn(0, 100)}%", color = SpokifyMuted, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = updates::cancelDownload) { Text("Cancel download") }
                } else if (state.phase != UpdatePhase.READY) {
                    Button(onClick = updates::download, colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) { Text("Download update") }
                }
                if (update.notes.isNotBlank()) {
                    Text("What's new", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    Text(update.notes, color = SpokifyMuted)
                }
            }
            if (state.phase == UpdatePhase.READY) {
                Button(enabled = !installBusy, onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                    catch (_: Exception) { installError = "Open Android settings and allow Spokify to install app updates, then try again." }
                }, colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen, contentColor = Color.Black)) {
                    Text(if (installBusy) "Preparing installer…" else "Install update")
                }
            }
            (state.error ?: installError)?.let { Text(it, color = SpokifyError, modifier = Modifier.padding(vertical = 12.dp)) }
            if (state.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) {
                TextButton(onClick = { installError = null; updates.check() }) { Text("Check for updates") }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATES_REPOSITORY}/releases"))) }
                    .onFailure { installError = "Couldn't open the releases page. Check your browser." }
            }) { Text("View releases on GitHub") }
        }
    }
}
