package com.mitansh.sidea.ui

import com.mitansh.sidea.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitansh.sidea.data.youtube.YouTubeCatalog
import androidx.lifecycle.ViewModelProvider

data class SearchUiState(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val recent: List<RecentSearch> = emptyList(),
)

class SearchCoordinator(
    private val scope: CoroutineScope,
    private val history: SearchHistory = SearchHistory(),
    private val debounceMs: Long = 350,
    private val lookup: suspend (String) -> List<Track>,
) {
    private val mutableState = MutableStateFlow(SearchUiState(recent = history.list()))
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L

    fun updateQuery(query: String) {
        start(query, debounceMs)
    }

    fun search(query: String = mutableState.value.query) {
        if (query.isNotBlank()) history.query(query)
        start(query, 0)
    }

    fun recordSelection(track: Track) { history.song(track); refreshHistory() }
    fun removeRecent(key: String) { history.remove(key); refreshHistory() }
    fun clearHistory() { history.clear(); refreshHistory() }
    private fun refreshHistory() { mutableState.value = mutableState.value.copy(recent = history.list()) }

    private fun start(query: String, waitMs: Long) {
        request?.cancel()
        val current = ++generation
        mutableState.value = mutableState.value.copy(query = query, results = emptyList(), loading = query.isNotBlank(), error = null, recent = history.list())
        if (query.isBlank()) return
        request = scope.launch {
            try {
                if (waitMs > 0) delay(waitMs)
                val result = lookup(query.trim())
                if (generation == current) mutableState.value = mutableState.value.copy(results = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == current) mutableState.value = mutableState.value.copy(error = failure.message ?: "Search failed")
            } finally {
                if (generation == current) mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }
}

class SearchViewModel(catalog: YouTubeCatalog, history: SearchHistory) : ViewModel() {
    val searches = SearchCoordinator(viewModelScope, history, lookup = catalog::search)
}

class SearchViewModelFactory(private val catalog: YouTubeCatalog, private val history: SearchHistory) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(catalog, history) as T
}
