package com.mitansh.sidea.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import com.mitansh.sidea.R
import com.mitansh.sidea.data.offline.OfflineMetadata
/**
 * The notification that shows download progress.
 *
 * Kept apart from the service so the text and the progress can be reasoned about without a live
 * player. Only downloads that are actually running are counted, so a finished download does not
 * keep the notification alive.
 */
object OfflineNotifications {

    fun channelName(context: Context): String = context.getString(R.string.offline_download_channel)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(OfflineDownloadService.CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            OfflineDownloadService.CHANNEL_ID,
            channelName(context),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        ensureChannel(context)
        val running = downloads.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val title = if (notMetRequirements != 0) context.getString(R.string.offline_download_waiting)
        else context.getString(R.string.offline_download_active)
        val builder = NotificationCompat.Builder(context, OfflineDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val first = running.firstOrNull()
        if (first == null) {
            builder.setContentText(context.getString(R.string.offline_download_active))
        } else {
            val metadata = OfflineMetadata.decode(first.request.data)
            val name = metadata?.let { "${it.artist} — ${it.title}" } ?: first.request.id
            builder.setContentText(name)
            val total = first.contentLength
            if (total > 0L) {
                val downloaded = first.getBytesDownloaded()
                builder.setProgress(100, (downloaded * 100L / total).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }
}
