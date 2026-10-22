package com.mitansh.sidea.data.offline

import com.mitansh.sidea.core.Track

/** One row in the offline (Downloads) list, built from the persisted download index. */
data class OfflineItem(
    val id: String,
    val track: Track,
    val automatic: Boolean,
    val status: OfflineStatus,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val percent: Float,
    /** Whether the user asked for this song never to be saved automatically. */
    val neverAutomatic: Boolean = false,
) {
    val isComplete: Boolean get() = status == OfflineStatus.COMPLETED

    /** Stored size when known, otherwise the bytes written so far. */
    val sizeBytes: Long get() = if (contentLength > 0L) contentLength else bytesDownloaded
}

/** Everything the Downloads UI needs in one observable value. */
data class OfflineState(
    val items: List<OfflineItem> = emptyList(),
    val automaticEnabled: Boolean = true,
    /** Whether automatic saves may use a metered connection. */
    val allowMetered: Boolean = false,
    val waitingForRequirements: Boolean = false,
    val automaticBytes: Long = 0L,
    val storageLimitBytes: Long = AutomaticDownloadPolicy.CAP_BYTES,
) {
    val completedCount: Int get() = items.count { it.isComplete }
    val completedBytes: Long get() = items.filter { it.isComplete }.sumOf { it.sizeBytes }
}
