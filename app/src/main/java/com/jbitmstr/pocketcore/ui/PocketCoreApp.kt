package com.jbitmstr.pocketcore.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbitmstr.pocketcore.core.NodeController

/**
 * Four destinations: the node dashboard, the sync tools, the chain explorer and
 * the settings. The debug log lives inside SETTINGS so the tab bar stays clean.
 */
private val tabs = listOf("NODE", "SYNC", "EXPLORE", "SETTINGS")

@Composable
fun PocketCoreApp() {
    val state by NodeController.state.collectAsStateWithLifecycle()
    val config by NodeController.settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Plain mutableStateOf: rememberSaveable can only store Bundle-compatible
    // values, and a MutableIntState is not one of them.
    var tab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            NodeController.consumeMessage()
        }
    }

    Scaffold(
        containerColor = PocketColors.Background,
        // The activity draws edge to edge, so the Scaffold itself has to keep the
        // status bar, the display cutout and the system navigation bar clear of
        // app UI. The bottom bar takes care of its own navigation bar inset.
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { BottomBar(tab) { tab = it } }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                0 -> NodeScreen(
                    state = state,
                    onStart = { force -> NodeController.start(force) },
                    onStop = { NodeController.stop() }
                )
                1 -> SyncScreen(
                    state = state,
                    onPickSnapshot = { uri, name -> NodeController.importSnapshot(uri, name) },
                    onLoadSnapshot = { NodeController.loadSnapshot() },
                    onClearSnapshot = { NodeController.clearSnapshot() }
                )
                2 -> ExploreScreen(
                    state = state,
                    onOpenSettings = { tab = 3 }
                )
                else -> SettingsScreen(
                    state = state,
                    config = config,
                    onNetworkChange = { network ->
                        NodeController.settings.setNetwork(network)
                        NodeController.applySettingsAndMaybeRestart()
                    },
                    onPruneChange = { value ->
                        NodeController.settings.setPruneMb(value)
                        NodeController.applySettingsAndMaybeRestart()
                    },
                    onWifiOnlyChange = { value ->
                        NodeController.settings.setWifiOnly(value)
                        NodeController.applySettingsAndMaybeRestart(restartIfRunning = false)
                    },
                    onChargingOnlyChange = { value ->
                        NodeController.settings.setChargingOnly(value)
                        NodeController.applySettingsAndMaybeRestart(restartIfRunning = false)
                    },
                    onRebuildAsFull = { NodeController.rebuildChainAsFull() },
                    onWipeData = { NodeController.wipeNodeData() }
                )
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    // WindowInsets.safeDrawing covers a three-button navigation bar at the bottom
    // (portrait) and on the side (landscape), the gesture handle and the display
    // cutout. The padding sits inside the bar's black background, so the tabs stay
    // above the system buttons while the black canvas still runs behind them.
    val insets = WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(PocketColors.Background)
            .windowInsetsPadding(insets)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PocketColors.Hairline)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(PocketColors.Background)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        color = if (active) PocketColors.Text else PocketColors.Faint,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .height(2.dp)
                            .fillMaxWidth(if (active) 0.45f else 0f)
                            .background(if (active) PocketColors.Accent else Color.Transparent)
                    )
                }
            }
        }
    }
}
