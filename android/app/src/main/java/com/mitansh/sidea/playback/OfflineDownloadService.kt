package com.mitansh.sidea.playback

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.mitansh.sidea.R
import com.mitansh.sidea.data.offline.OfflineRepository

/**
 * Runs the download transfers in the background.
 *
 * Media3 owns the transfer state; this service only keeps the transfers alive while the app is
 * not in the foreground and shows their progress. The download requests themselves are created by
 * [OfflineRepository], so a download started from the player and one started from the Downloads
 * screen behave identically.
 */
@UnstableApi
class OfflineDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.offline_download_channel,
    R.drawable.ic_download,
) {
    override fun getDownloadManager(): androidx.media3.exoplayer.offline.DownloadManager =
        OfflineRepository.get(this).downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification = OfflineNotifications.build(this, downloads, notMetRequirements)

    override fun onTaskRemoved(rootIntent: Intent) {
        // A removed task must not cancel transfers the user asked for; the repository owns them.
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val NOTIFICATION_ID = 3411
        const val CHANNEL_ID = "offline_downloads"
    }
}
