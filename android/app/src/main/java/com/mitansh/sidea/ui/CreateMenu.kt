package com.mitansh.sidea.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R

/** Anchored Create card leaves the navigation visible, as observed on the reference phone. */
@Composable
fun CreateMenu(visible: Boolean, current: SpokifyTab, onDismiss: () -> Unit,
    onSelect: (SpokifyTab) -> Unit, onPlaylist: () -> Unit, onImport: () -> Unit) {
    BackHandler(visible, onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(motionTween(180)), exit = fadeOut(motionTween(140))) {
        Box(Modifier.fillMaxSize().semantics { paneTitle = "Create" }) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close create menu", onClick = onDismiss))
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                Surface(color = Color(0xFF202020), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                        .animateEnterExit(enter = slideInVertically(motionTween(240)) { it / 3 }, exit = slideOutVertically(motionTween(160)) { it / 4 })) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        CreateAction("Playlist", "Create a playlist with your favourite songs", R.drawable.ic_music, onPlaylist)
                        CreateAction("Import a playlist", "Bring music from a public playlist link", R.drawable.ic_download, onImport)
                    }
                }
                Box {
                    SpokifyNavigationBar(current, onSelect, onCreate = onDismiss)
                    Surface(color = Color.White, shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd)
                        .padding(end = 16.dp).size(44.dp).motionClickable(onClick = onDismiss)) {
                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_close), "Close create menu", Modifier.size(27.dp), tint = Color.Black) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateAction(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).background(Color(0xFF404040), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(26.dp), tint = Color(0xFFB3B3B3))
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(subtitle, color = SpokifyMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}
