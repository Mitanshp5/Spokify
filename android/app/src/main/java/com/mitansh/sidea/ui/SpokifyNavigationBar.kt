package com.mitansh.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mitansh.sidea.R

enum class SpokifyTab(val label: String, val icon: Int, val outline: Int = icon) {
    HOME("Home", R.drawable.ic_home, R.drawable.ic_home_outline),
    SEARCH("Search", R.drawable.ic_search),
    LIBRARY("Your Library", R.drawable.ic_library, R.drawable.ic_library_outline),
}

// Scroll containers add this padding so their final items clear the floating chrome.
val LocalBottomContentPadding = compositionLocalOf { 0.dp }
val SpokifyNavigationHeight = 64.dp

/** Content remains visible through the upper edge, fading to black beneath the labels. */
@Composable
fun SpokifyNavigationBar(current: SpokifyTab, onSelect: (SpokifyTab) -> Unit,
    modifier: Modifier = Modifier, onCreate: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(SpokifyNavigationHeight)
        .background(Brush.verticalGradient(0f to Color.Transparent, .3f to Color.Black.copy(alpha = .8f), 1f to Color.Black))
        .selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        SpokifyTab.entries.forEach { tab ->
            NavigationDestination(tab.label, if (tab == current) tab.icon else tab.outline,
                tab == current, { onSelect(tab) }, Modifier.weight(1f))
        }
        NavigationDestination("Create", R.drawable.ic_add, false, onCreate, Modifier.weight(1f))
    }
}

@Composable
private fun NavigationDestination(label: String, icon: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f, motionTween(110), label = "navigation press")
    val color by animateColorAsState(if (selected) Color.White else Color(0xFFB3B3B3), motionTween(160), label = "navigation color")
    Column(modifier.fillMaxHeight().selectable(selected, interactionSource = interaction, indication = null,
        role = Role.Tab, onClick = onClick).padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), null, Modifier.size(27.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = color)
        Text(label, color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
