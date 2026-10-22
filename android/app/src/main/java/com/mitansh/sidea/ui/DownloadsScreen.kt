package com.mitansh.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R
import com.mitansh.sidea.data.offline.OfflineItem
import com.mitansh.sidea.data.offline.OfflineState
import com.mitansh.sidea.data.offline.OfflineStatus

/**
 * The Downloads screen: what is saved, how much space it uses, and the budget for automatic saving.
 *
 * Automatic downloads and manual downloads are shown as one list but are separate ownership
 * classes, mirroring the official app's split between its playable cache and its download service.
 * Only automatic content counts against the budget.
 */
@Composable
fun DownloadsScreen(
    state: OfflineState,
    limitBytes: Long,
    onSetLimit: (Long) -> Unit,
    onAutomaticEnabled: (Boolean) -> Unit,
    onAllowMetered: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onKeep: (String) -> Unit,
    onNeverAutomatic: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    var showLimit by remember { mutableStateOf(false) }

    Column(modifier.padding(bottom = LocalBottomContentPadding.current)) {
        ScreenHeader(title = "Downloads", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Automatic downloads", color = Color.White, fontSize = 16.sp)
                Text(
                    describeAutomaticUsage(state, limitBytes),
                    color = SpokifyMuted, fontSize = 13.sp,
                )
            }
            Switch(checked = state.automaticEnabled, onCheckedChange = onAutomaticEnabled)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Save songs you play often so they are ready offline.",
                color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showLimit = true }) { Text("Set limit") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Allow automatic saves on mobile data.",
                color = SpokifyMuted, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Switch(checked = state.allowMetered, onCheckedChange = onAllowMetered)
        }
        if (state.items.isEmpty()) {
            Text(
                "Nothing downloaded yet. Use the download action on a song to save it.",
                color = SpokifyMuted, modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.items, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onRemove = { onRemove(item.id) },
                        onRetry = { onRetry(item.id) },
                        onKeep = { onKeep(item.id) },
                        onNeverAutomatic = { onNeverAutomatic(item.id, !item.neverAutomatic) },
                    )
                }
            }
        }
    }

    if (showLimit) {
        StorageLimitDialog(current = limitBytes, onDismiss = { showLimit = false }) { chosen ->
            onSetLimit(chosen)
            showLimit = false
        }
    }
}

@Composable
private fun DownloadRow(
    item: OfflineItem,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onKeep: () -> Unit,
    onNeverAutomatic: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.track.title, color = Color.White, maxLines = 1)
            Text(item.track.artist, color = SpokifyMuted, fontSize = 12.sp, maxLines = 1)
            Text(
                describeDownload(item) + if (item.automatic) " · automatic" else "",
                color = SpokifyMuted, fontSize = 12.sp, maxLines = 1,
            )
            if (item.status == OfflineStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = SpokifyGreen,
                )
            }
            if (item.automatic) {
                TextButton(onClick = onKeep) { Text("Keep downloaded") }
            }
            TextButton(onClick = onNeverAutomatic) {
                Text(if (item.neverAutomatic) "Allow automatic saving" else "Never auto-download")
            }
        }
        MotionIconButton(onClick = if (item.status == OfflineStatus.FAILED) onRetry else onRemove) {
            Icon(
                painterResource(
                    if (item.status == OfflineStatus.FAILED) R.drawable.ic_retry else R.drawable.ic_download
                ),
                contentDescription = if (item.status == OfflineStatus.FAILED) {
                    "Retry download"
                } else {
                    "Remove download"
                },
                tint = SpokifyMuted,
            )
        }
    }
}

/**
 * The storage budget dialog.
 *
 * The presets cover the usual choices, and a custom size is there for anyone who wants something
 * else.
 */
@Composable
private fun StorageLimitDialog(current: Long, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val options = listOf(
        250L * 1024 * 1024,
        500L * 1024 * 1024,
        1024L * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        5L * 1024 * 1024 * 1024,
    )
    var custom by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic download limit") },
        text = {
            Column {
                Text(
                    "Songs you play often are saved automatically up to this size.",
                    color = SpokifyMuted,
                )
                options.forEach { option ->
                    val label = formatBytes(option)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { onChoose(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (option == current) SpokifyGreen else Color.White)
                    }
                }
                if (showCustom) {
                    OutlinedTextField(
                        custom, { custom = it },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Custom size in MiB") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                    )
                    Button(
                        onClick = {
                            val mib = custom.toLongOrNull()
                            if (mib != null && mib > 0) onChoose(mib * 1024 * 1024)
                        },
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpokifyGreen),
                    ) { Text("Use this size") }
                } else {
                    TextButton(onClick = { showCustom = true }) { Text("Custom size") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
