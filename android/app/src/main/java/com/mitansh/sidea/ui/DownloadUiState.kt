package com.mitansh.sidea.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.mitansh.sidea.data.offline.OfflineItem
import com.mitansh.sidea.data.offline.OfflineState

/** Progress changes only the state cell read by that song, not every list row. */
@Stable
class DownloadUiState {
    private val items = mutableStateMapOf<String, androidx.compose.runtime.MutableState<OfflineItem>>()
    var completedItems: List<OfflineItem> by mutableStateOf(emptyList())
        private set

    operator fun get(id: String): OfflineItem? = items[id]?.value

    fun update(state: OfflineState) {
        Snapshot.withMutableSnapshot {
            val incoming = state.items.associateBy { it.id }
            (items.keys - incoming.keys).forEach(items::remove)
            incoming.forEach { (id, item) ->
                val cell = items[id]
                if (cell == null) items[id] = mutableStateOf(item)
                else cell.value = item
            }
            completedItems = state.items.filter { it.isComplete }
        }
    }
}
