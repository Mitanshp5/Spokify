package com.mitansh.sidea.data.offline

/** Coarse UI state of one offline item. */
enum class OfflineStatus { QUEUED, WAITING, DOWNLOADING, STOPPED, COMPLETED, FAILED, REMOVING }

/**
 * Maps persisted download state and counters to what the UI shows.
 *
 * Completion is never inferred from a URL or a queued request: it requires Media3's
 * [androidx.media3.exoplayer.offline.Download.STATE_COMPLETED] state and, when the size is known,
 * the full byte count.
 */
object OfflineStatusMapper {

    fun status(state: Int, waitingForRequirements: Boolean): OfflineStatus = when (state) {
        DownloadState.STATE_DOWNLOADING -> OfflineStatus.DOWNLOADING
        DownloadState.STATE_QUEUED, DownloadState.STATE_RESTARTING ->
            if (waitingForRequirements) OfflineStatus.WAITING else OfflineStatus.QUEUED
        DownloadState.STATE_STOPPED -> OfflineStatus.STOPPED
        DownloadState.STATE_COMPLETED -> OfflineStatus.COMPLETED
        DownloadState.STATE_FAILED -> OfflineStatus.FAILED
        DownloadState.STATE_REMOVING -> OfflineStatus.REMOVING
        else -> OfflineStatus.QUEUED
    }

    /** True only for genuinely finished content, guarding against a truncated cache entry. */
    fun isComplete(state: Int, bytesDownloaded: Long, contentLength: Long): Boolean =
        state == DownloadState.STATE_COMPLETED && (contentLength <= 0L || bytesDownloaded >= contentLength)

    /** Fraction downloaded in 0..1, or -1 when the total size is unknown. */
    fun percent(bytesDownloaded: Long, contentLength: Long): Float =
        if (contentLength <= 0L) -1f else (bytesDownloaded.toFloat() / contentLength).coerceIn(0f, 1f)
}
