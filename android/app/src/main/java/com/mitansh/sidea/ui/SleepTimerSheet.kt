package com.mitansh.sidea.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SleepTimerSheet(
    state: SleepTimerUiState,
    onMinutes: (Int) -> Unit,
    onEndOfSong: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf("") }
    val minutes = sleepTimerCustomMinutes(custom)
    val enabled = state.ready && !state.pending
    MotionBottomSheet(onDismiss = onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Sleep timer", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(if (!state.ready && state.status != null) "Last confirmed: ${state.summary}" else state.summary,
                color = if (state.status?.active == true && state.ready) SpokifyGreen else SpokifyMuted,
                modifier = Modifier.padding(vertical = 12.dp))
            if (state.pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Updating sleep timer…", color = SpokifyMuted, modifier = Modifier.padding(vertical = 8.dp))
            }
            state.error?.let {
                Text(it, color = SpokifyError, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onRefresh, enabled = !state.pending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh timer status") }
            }
            Text("A new setting replaces the current timer.", color = SpokifyMuted, fontSize = 12.sp)
            sleepTimerPresets.forEach { preset ->
                TextButton(onClick = { onMinutes(preset) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("$preset minutes")
                }
            }
            TextButton(onClick = onEndOfSong, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("End of this song") }
            Text("Skipping to another song cancels the end-of-song timer.", color = SpokifyMuted, fontSize = 12.sp)
            OutlinedTextField(
                value = custom, onValueChange = { custom = it }, enabled = !state.pending,
                label = { Text("Custom minutes (1–180)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = custom.isNotEmpty() && minutes == null,
                supportingText = { Text(if (custom.isNotEmpty() && minutes == null) "Enter a whole number from 1 to 180." else "1–180 minutes") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(onClick = { minutes?.let(onMinutes) }, enabled = enabled && minutes != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Set custom sleep timer" }) { Text("Set timer") }
            if (state.status?.active == true) {
                TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel timer") }
            }
        }
    }
}
