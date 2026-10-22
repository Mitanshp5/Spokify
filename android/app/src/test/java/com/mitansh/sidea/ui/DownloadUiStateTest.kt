package com.mitansh.sidea.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.mitansh.sidea.core.Track
import com.mitansh.sidea.data.offline.OfflineItem
import com.mitansh.sidea.data.offline.OfflineState
import com.mitansh.sidea.data.offline.OfflineStatus
import org.junit.Assert.*
import org.junit.Test

class DownloadUiStateTest {
    private fun item(id: String, percent: Float = 0f, complete: Boolean = false) = OfflineItem(
        id, Track(id, id, "Artist"), false,
        if (complete) OfflineStatus.COMPLETED else OfflineStatus.DOWNLOADING,
        (percent * 1000).toLong(), 1000, percent,
    )

    @Test fun progressInvalidatesOnlyItsSongAndLeavesCompletedSummaryAlone() {
        val state = DownloadUiState()
        val one = item("one")
        val two = item("two")
        val ready = item("ready", 1f, true)
        state.update(OfflineState(items = listOf(one, two, ready)))
        Snapshot.sendApplyNotifications()
        val originalCompleted = state.completedItems
        var oneChanges = 0
        var twoChanges = 0
        var summaryChanges = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> oneChanges++ }) { state["one"] }
            observer.observeReads(Any(), { _: Any -> twoChanges++ }) { state["two"] }
            observer.observeReads(Any(), { _: Any -> summaryChanges++ }) { state.completedItems }
            state.update(OfflineState(items = listOf(one.copy(percent = .5f, bytesDownloaded = 500), two, ready)))
            Snapshot.sendApplyNotifications()
            assertEquals(1, oneChanges)
            assertEquals(0, twoChanges)
            assertEquals(0, summaryChanges)
            assertSame(originalCompleted, state.completedItems)
            assertEquals(.5f, state["one"]!!.percent)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun completionRemovalAndRetryRemainObservable() {
        val state = DownloadUiState()
        state.update(OfflineState(items = listOf(item("one"))))
        assertTrue(state.completedItems.isEmpty())
        state.update(OfflineState(items = listOf(item("one", 1f, true))))
        assertEquals(listOf("one"), state.completedItems.map { it.id })
        state.update(OfflineState())
        assertNull(state["one"])
        assertTrue(state.completedItems.isEmpty())
        state.update(OfflineState(items = listOf(item("one", .1f))))
        assertEquals(OfflineStatus.DOWNLOADING, state["one"]!!.status)
    }
}
