package com.mitansh.sidea.updates

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mitansh.sidea.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

internal enum class UpdatePhase { IDLE, CHECKING, NO_RELEASE, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val update: AppUpdate? = null,
    val received: Long = 0,
    val file: File? = null,
    val error: String? = null,
)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val source by lazy { GitHubUpdateSource(BuildConfig.UPDATES_REPOSITORY) }
    private val mutableState = MutableStateFlow(UpdateState())
    internal val state = mutableState.asStateFlow()
    internal val available = state.map { it.update != null }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var operation: Job? = null
    private var dailyJob: Job? = null
    private val context get() = getApplication<Application>()
    private val preferences by lazy { context.getSharedPreferences("app-updates", Context.MODE_PRIVATE) }
    private val gate = DailyUpdateGate({ preferences.getString("last-attempt-day", null) },
        { preferences.edit().putString("last-attempt-day", it).commit() })
    private val cached = viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        try { preferences.getString("available-release", null)?.let {
            decodeCachedUpdate(it, BuildConfig.UPDATES_REPOSITORY, context.packageName, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
        } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }

    /** onStart also covers returning to an activity kept alive across midnight. */
    internal fun checkDaily(day: LocalDate = LocalDate.now()) {
        if (dailyJob?.isActive == true) return
        dailyJob = viewModelScope.launch {
            try {
                delay(1_800) // Let the first screen render; no update request blocks startup.
                restoreCached()
                if (operation?.isActive == true) return@launch
                if (withContext(Dispatchers.IO) { gate.claim(day) }) check()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Automatic checks must not interrupt listening. */ }
        }
    }

    private suspend fun restoreCached() {
        val update = cached.await()
        if (mutableState.value.phase == UpdatePhase.IDLE && update != null)
            mutableState.value = UpdateState(UpdatePhase.AVAILABLE, update)
    }

    internal fun check() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            restoreCached()
            val previous = mutableState.value
            mutableState.value = previous.copy(phase = UpdatePhase.CHECKING, error = null)
            try {
                withContext(Dispatchers.IO) { gate.claim(LocalDate.now()) }
                val update = withContext(Dispatchers.IO) { source.latest() }
                val next = when {
                    update == null -> UpdateState(UpdatePhase.NO_RELEASE)
                    update.manifest.applicationId != context.packageName -> throw IllegalArgumentException("This release belongs to a different app.")
                    update.manifest.versionCode <= BuildConfig.VERSION_CODE -> UpdateState(UpdatePhase.CURRENT)
                    update.manifest.minSdk > Build.VERSION.SDK_INT -> throw IllegalArgumentException("The latest update requires a newer Android version.")
                    else -> UpdateState(UpdatePhase.AVAILABLE, update)
                }
                withContext(Dispatchers.IO) {
                    preferences.edit().apply {
                        if (next.update == null) remove("available-release")
                        else putString("available-release", next.update.encode())
                    }.commit()
                }
                mutableState.value = next
            } catch (cancel: CancellationException) {
                mutableState.value = previous
                throw cancel
            } catch (failure: Exception) {
                mutableState.value = previous.copy(phase = UpdatePhase.ERROR, error = message(failure))
            }
        }
    }

    internal fun download() {
        if (operation?.isActive == true) return
        val update = mutableState.value.update ?: return
        operation = viewModelScope.launch {
            mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, update)
            var partial: File? = null
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    val folder = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
                    folder.listFiles()?.filter { it.extension == "part" || it.name.endsWith(".part.apk") }?.forEach { it.delete() }
                    val target = File(folder, "${update.manifest.versionCode}-${update.manifest.sha256}.apk")
                    if (target.exists()) {
                        try { verify(target, update); return@withContext target }
                        catch (_: IllegalArgumentException) { check(target.delete()) }
                    }
                    partial = File(folder, "download.part.apk")
                    var lastProgress = 0L
                    source.download(update, partial!!) { received ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 100 || received == update.manifest.sizeBytes) {
                            mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, update, received)
                            lastProgress = now
                        }
                    }
                    verify(partial!!, update)
                    check(partial!!.renameTo(target)) { "Couldn't save the update. Free some storage and try again." }
                    folder.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach { it.delete() }
                    target
                }
                mutableState.value = UpdateState(UpdatePhase.READY, update, update.manifest.sizeBytes, downloaded)
            } catch (cancel: CancellationException) {
                mutableState.value = UpdateState(UpdatePhase.AVAILABLE, update)
                throw cancel
            } catch (failure: Exception) {
                mutableState.value = UpdateState(UpdatePhase.ERROR, update, error = message(failure))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { partial?.delete() }
            }
        }
    }

    internal fun cancelDownload() { operation?.cancel() }

    internal suspend fun installIntent(): Intent = withContext(Dispatchers.IO) {
        val ready = mutableState.value
        val file = requireNotNull(ready.file) { "Download the update again." }
        verify(file, requireNotNull(ready.update))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Spokify update", uri) }
    }

    private fun verify(file: File, update: AppUpdate) {
        verifyUpdateFile(file, update.manifest)
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("The update is not a valid Android package.")
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, flags)
        validateUpdateIdentity(update.manifest, identity(archive), identity(installed), Build.VERSION.SDK_INT)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            info.applicationInfo?.minSdkVersion ?: 0, signers.orEmpty().map { it.toCharsString() }.toSet())
    }

    private fun message(failure: Exception) = if (failure is IllegalArgumentException || failure is IllegalStateException)
        failure.message ?: "Couldn't prepare the update. Try again."
    else if (failure is java.net.UnknownHostException || failure is java.net.SocketTimeoutException)
        "Couldn't connect. Check your internet connection and try again."
    else failure.message?.take(200) ?: "Couldn't download the update. Try again."
}

class UpdateFileProvider : FileProvider()
