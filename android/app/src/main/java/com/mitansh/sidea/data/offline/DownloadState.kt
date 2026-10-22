package com.mitansh.sidea.data.offline

/**
 * Mirror of the Media3 [androidx.media3.exoplayer.offline.Download] state values.
 *
 * These constants are persisted into the download index and must stay identical to Media3's. They
 * are duplicated here so the storage-cap policy stays free of Android classes for unit testing;
 * `OfflineStatusTest` pins the two sets together.
 */
object DownloadState {
    const val STATE_QUEUED = 0
    const val STATE_STOPPED = 1
    const val STATE_DOWNLOADING = 2
    const val STATE_COMPLETED = 3
    const val STATE_FAILED = 4
    const val STATE_REMOVING = 5
    const val STATE_RESTARTING = 7
}
