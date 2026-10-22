package com.mitansh.sidea.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The radio settings.
 *
 * Autoplay decides whether the radio keeps filling the queue by itself, and the hidden list holds
 * the songs the user never wants to hear again.
 */
@Composable
fun RadioSettingsScreen(
    autoplayEnabled: Boolean,
    hiddenCount: Int,
    onAutoplayEnabled: (Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = LocalBottomContentPadding.current)) {
        ScreenHeader(title = "Listening preferences", onBack = onBack)
        QuietNotificationControls()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Autoplay", color = Color.White, fontSize = 16.sp)
                Text(
                    "Keep filling the queue with similar songs.",
                    color = SpokifyMuted, fontSize = 13.sp,
                )
            }
            Switch(checked = autoplayEnabled, onCheckedChange = onAutoplayEnabled)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hidden songs", color = Color.White, fontSize = 16.sp)
                Text(
                    if (hiddenCount == 0) "No songs are hidden." else "$hiddenCount songs will not be suggested.",
                    color = SpokifyMuted, fontSize = 13.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = onReset) { Text("Reset radio preferences") }
        }
    }
}
