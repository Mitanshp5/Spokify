package com.mitansh.sidea.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.imports.ImportCoordinator
import com.mitansh.sidea.data.imports.ImportSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Import-only callback owner; selections are never held separately from the durable session. */
internal class ImportReviewDraftState(private val coordinator: ImportCoordinator) {
    var session by mutableStateOf<ImportSessionStore.Session?>(null)
        private set
    var pending by mutableStateOf<List<ImportSessionStore.Session>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var choosing by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var choiceMessage by mutableStateOf<String?>(null)
        private set

    private val writes = Mutex()
    private var readGeneration = 0L
    private var readJob: Job? = null

    suspend fun refreshPending() {
        pending = withContext(Dispatchers.IO) { coordinator.pendingSessions() }
    }

    suspend fun resume(id: String) {
        if (loading || choosing) return
        refreshPending()
        if (loading || choosing) return
        val draft = pending.firstOrNull { it.id == id }
        if (draft == null) error = "That unfinished import is no longer available. Read the playlist again."
        else { session = draft; error = null; choiceMessage = null }
    }

    suspend fun read(link: String) {
        if (loading || choosing) return
        val generation = ++readGeneration
        readJob = currentCoroutineContext()[Job]
        loading = true
        session = null
        error = null
        choiceMessage = null
        progress = "Reading the playlist…"
        try {
            val result = coordinator.read(link) { message ->
                withContext(Dispatchers.Main.immediate) { if (generation == readGeneration) progress = message }
            }
            if (generation != readGeneration) return
            session = result.session.takeIf { it.items.isNotEmpty() }
            error = result.error ?: "No songs were available in this playlist.".takeIf { result.session.items.isEmpty() }
            refreshPending()
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            if (generation == readGeneration) error = "The playlist could not be read. Please try again."
        } finally { if (generation == readGeneration) { loading = false; progress = null; readJob = null } }
    }

    suspend fun choose(position: Int, track: Track) {
        writes.withLock {
            val draft = session ?: return@withLock
            choosing = true
            error = null
            choiceMessage = null
            try {
                val result = coordinator.updateSelection(draft.id, position, track)
                if (result.session != null) {
                    session = result.session
                    choiceMessage = "Song ${position + 1} is ready · ${importReviewTitle(result.session.items.single { it.position == position }.selected!!.title)} selected"
                    refreshPending()
                } else error = result.error ?: "The choice could not be saved. Try choosing again."
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { error = "The choice could not be saved. Free some storage and try choosing again."
            } finally { choosing = false }
        }
    }

    suspend fun save(onSaved: suspend (ImportCoordinator.CommitResult) -> Unit) {
        val committed = writes.withLock {
            val draft = session ?: return@withLock null
            loading = true
            error = null
            try {
                val result = coordinator.commit(draft.id)
                if (result.error != null || result.playlistId == null) {
                    error = result.error ?: "The playlist could not be saved. Your review is kept."
                    null
                } else {
                    refreshPending()
                    session = null
                    choiceMessage = null
                    result
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) {
                error = "The playlist could not be saved. Please try again. Your review is kept."
                null
            } finally { loading = false }
        }
        if (committed != null) onSaved(committed)
    }

    fun cancelReading() {
        ++readGeneration
        readJob?.cancel()
        readJob = null
        // Only a fetch has progress; don't unlock an in-flight database commit.
        if (progress != null) loading = false
        progress = null
    }

    fun newLink() {
        if (loading || choosing) return
        session = null
        error = null
        choiceMessage = null
    }
}
