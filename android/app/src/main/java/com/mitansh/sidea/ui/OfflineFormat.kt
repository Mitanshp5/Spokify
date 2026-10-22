package com.mitansh.sidea.ui

import androidx.compose.ui.graphics.Color
import com.mitansh.sidea.data.offline.OfflineItem
import com.mitansh.sidea.data.offline.OfflineState
import com.mitansh.sidea.data.offline.OfflineStatus

/** The app's accent colour, shared by every screen. */
val Green = Color(0xFF1ED760)

/** Formats a byte count for the storage and download screens. */
fun formatBytes(value: Long): String {
    if (value < 1024L) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var scaled = value.toDouble()
    var index = -1
    while (scaled >= 1024.0 && index < units.lastIndex) {
        scaled /= 1024.0
        index++
    }
    val pattern = if (scaled >= 100.0) "%.0f %s" else "%.1f %s"
    return String.format(java.util.Locale.US, pattern, scaled, units[index])
}

/** A short, honest description of one download's state. */
fun describeDownload(item: OfflineItem): String = when (item.status) {
    OfflineStatus.COMPLETED -> "Downloaded · ${formatBytes(item.sizeBytes)}"
    OfflineStatus.DOWNLOADING -> if (item.percent >= 0f) {
        "Downloading ${(item.percent * 100).toInt()}%"
    } else {
        "Downloading ${formatBytes(item.bytesDownloaded)}"
    }
    OfflineStatus.QUEUED -> "Queued"
    OfflineStatus.WAITING -> "Waiting for a connection"
    OfflineStatus.STOPPED -> "Paused"
    OfflineStatus.FAILED -> "Failed"
    OfflineStatus.REMOVING -> "Removing"
}

/** The storage line shown above the automatic-download budget. */
fun describeAutomaticUsage(state: OfflineState, limitBytes: Long): String {
    val used = formatBytes(state.automaticBytes)
    val limit = formatBytes(limitBytes)
    return "$used of $limit used by automatic downloads"
}
