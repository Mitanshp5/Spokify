package com.mitansh.sidea.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mitansh.sidea.playback.QuietPlaybackNotifications

@Composable
fun QuietNotificationControls() {
    val context = LocalContext.current
    val quiet = remember(context) { QuietPlaybackNotifications.get(context) }
    val status by quiet.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, quiet) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) quiet.refreshAccess()
        }
        lifecycle.addObserver(observer)
        quiet.refreshAccess()
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Quiet notifications while playing", color = Color.White, fontSize = 16.sp)
                Text("Silence notification sounds during music. Calls and alarms stay available; notifications remain visible.", color = SpokifyMuted, fontSize = 13.sp)
            }
            Switch(checked = status.enabled && status.permitted, enabled = status.supported,
                onCheckedChange = { enabled ->
                    quiet.setEnabled(enabled)
                    if (enabled && !status.permitted) quiet.requestAccess()
                })
        }
        if (!status.supported) {
            Text("Requires Android 10 or newer.", color = SpokifyMuted, fontSize = 12.sp)
        } else if (!status.permitted) {
            Text("Allow Spokify to control Do Not Disturb once in Android settings. Your other Do Not Disturb rules stay in place.", color = SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { quiet.setEnabled(true); quiet.requestAccess() }) { Text("Allow notification control") }
        } else {
            Text(when {
                !status.enabled -> "Off"
                status.active -> "Active during playback"
                else -> "Ready · turns off when playback stops"
            }, color = if (status.active) SpokifyGreen else SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        status.error?.let { Text(it, color = SpokifyError, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}
