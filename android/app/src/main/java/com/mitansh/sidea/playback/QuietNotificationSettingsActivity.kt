package com.mitansh.sidea.playback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import com.mitansh.sidea.ui.QuietNotificationControls
import com.mitansh.sidea.ui.ScreenHeader
import com.mitansh.sidea.ui.SpokifyInk
import com.mitansh.sidea.ui.SpokifyTheme

/** Android's automatic-rule settings link back to this small configuration screen. */
class QuietNotificationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpokifyTheme {
                Column(Modifier.fillMaxSize().background(SpokifyInk).systemBarsPadding()) {
                    ScreenHeader("Notification sounds", onBack = { finish() })
                    QuietNotificationControls()
                }
            }
        }
    }
}
