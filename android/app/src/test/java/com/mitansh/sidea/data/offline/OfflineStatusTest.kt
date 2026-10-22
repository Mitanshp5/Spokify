package com.mitansh.sidea.data.offline

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStatusTest {

    @Test
    fun localStateMirrorsPersistedMedia3Values() {
        assertEquals(Download.STATE_QUEUED, DownloadState.STATE_QUEUED)
        assertEquals(Download.STATE_STOPPED, DownloadState.STATE_STOPPED)
        assertEquals(Download.STATE_DOWNLOADING, DownloadState.STATE_DOWNLOADING)
        assertEquals(Download.STATE_COMPLETED, DownloadState.STATE_COMPLETED)
        assertEquals(Download.STATE_FAILED, DownloadState.STATE_FAILED)
        assertEquals(Download.STATE_REMOVING, DownloadState.STATE_REMOVING)
        assertEquals(Download.STATE_RESTARTING, DownloadState.STATE_RESTARTING)
    }

    @Test
    fun reportsCompletedOnlyForRealCompletedState() {
        assertEquals(OfflineStatus.COMPLETED, OfflineStatusMapper.status(Download.STATE_COMPLETED, waitingForRequirements = false))
        assertTrue(OfflineStatusMapper.isComplete(Download.STATE_COMPLETED, bytesDownloaded = 100, contentLength = 100))
    }

    @Test
    fun truncatedCompletedRecordIsNotTreatedAsComplete() {
        assertFalse(OfflineStatusMapper.isComplete(Download.STATE_COMPLETED, bytesDownloaded = 40, contentLength = 100))
    }

    @Test
    fun activeStatesAreDistinguished() {
        assertEquals(OfflineStatus.DOWNLOADING, OfflineStatusMapper.status(Download.STATE_DOWNLOADING, false))
        assertEquals(OfflineStatus.QUEUED, OfflineStatusMapper.status(Download.STATE_QUEUED, false))
        assertEquals(OfflineStatus.WAITING, OfflineStatusMapper.status(Download.STATE_QUEUED, waitingForRequirements = true))
        assertEquals(OfflineStatus.STOPPED, OfflineStatusMapper.status(Download.STATE_STOPPED, false))
        assertEquals(OfflineStatus.FAILED, OfflineStatusMapper.status(Download.STATE_FAILED, false))
        assertEquals(OfflineStatus.REMOVING, OfflineStatusMapper.status(Download.STATE_REMOVING, false))
        assertEquals(OfflineStatus.QUEUED, OfflineStatusMapper.status(Download.STATE_RESTARTING, false))
    }

    @Test
    fun progressIsUnknownUntilLengthIsKnown() {
        assertEquals(-1f, OfflineStatusMapper.percent(bytesDownloaded = 50, contentLength = -1), 0.001f)
        assertEquals(0.5f, OfflineStatusMapper.percent(bytesDownloaded = 50, contentLength = 100), 0.001f)
        assertEquals(1f, OfflineStatusMapper.percent(bytesDownloaded = 500, contentLength = 100), 0.001f)
    }
}
