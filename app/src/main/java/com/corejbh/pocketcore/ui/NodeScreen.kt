package com.corejbh.pocketcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.pocketcore.core.NodeController
import com.corejbh.pocketcore.core.NodeStatus
import com.corejbh.pocketcore.core.NodeUiState
import com.corejbh.pocketcore.core.PeerInfo

@Composable
fun NodeScreen(
    state: NodeUiState,
    onStart: (Boolean) -> Unit,
    onStop: () -> Unit
) {
    var expandedPeer by remember { mutableStateOf<String?>(null) }
    var pendingBan by remember { mutableStateOf<PeerInfo?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        SpacerH(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "PocketCore",
                    color = PocketColors.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                SpacerH(3)
                Text(
                    text = "${state.config.network.label} full node",
                    color = PocketColors.Muted,
                    fontSize = 11.sp
                )
            }
            StatusPill(state.status, state.detail, state.isSyncing)
        }
        SpacerH(16)

        HeroCard(state)
        SpacerH(12)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // START and STOP are the only controls: each is enabled in the states
            // it applies to, and the other stays visible but inert.
            val canStart = state.status == NodeStatus.STOPPED ||
                state.status == NodeStatus.ERROR ||
                state.status == NodeStatus.WAITING
            val canStop = state.status == NodeStatus.RUNNING ||
                state.status == NodeStatus.STARTING ||
                state.status == NodeStatus.WAITING
            ActionButton(
                text = if (state.status == NodeStatus.WAITING) "START NOW" else "START",
                onClick = { onStart(state.status == NodeStatus.WAITING) },
                enabled = canStart,
                filled = canStart,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                text = if (state.status == NodeStatus.STOPPING) "STOPPING" else "STOP",
                onClick = onStop,
                enabled = canStop,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.detail != null && state.status != NodeStatus.RUNNING) {
            SpacerH(10)
            Muted(state.detail, color = if (state.status == NodeStatus.ERROR) PocketColors.Error else PocketColors.Muted, size = 11)
        }

        state.portNote?.let { note ->
            SpacerH(10)
            NoteBanner(note, PocketColors.Warning)
        }

        // A node without its foreground service is still running, but Android may
        // stop it as soon as the screen is off. Said here rather than in a log so
        // the user knows what the app is not doing for them (versionCode 22).
        state.serviceError?.let { error ->
            SpacerH(10)
            NoteBanner(error, PocketColors.Error)
        }

        SpacerH(12)
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Peers (${state.peers.size})", Modifier.weight(1f))
                Text(
                    text = "OFFLINE FLAGS",
                    color = PocketColors.Faint,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
            SpacerH(8)
            if (state.peers.isEmpty()) {
                EmptyNote(if (state.status == NodeStatus.RUNNING) "No peers connected yet." else "Start the node to connect to the network.")
            } else {
                // Every connected peer is listed, not just the first few: the
                // card scrolls with the screen, and a peer list that stops at an
                // arbitrary row makes the connection count in the header wrong
                // about what it is showing.
                state.peers.forEach { peer ->
                    PeerRow(
                        peer = peer,
                        expanded = expandedPeer == peer.address,
                        onToggle = { expandedPeer = if (expandedPeer == peer.address) null else peer.address },
                        onDisconnect = {
                            NodeController.disconnectPeer(peer.address)
                            expandedPeer = null
                        },
                        onBan = { pendingBan = peer }
                    )
                }
                SpacerH(6)
                Muted(
                    "Country flags come from a table bundled in this app (DB-IP country lite). " +
                        "No address ever leaves the phone for a lookup.",
                    color = PocketColors.Faint
                )
            }
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Node")
            SpacerH(8)
            InfoRow("Network", state.config.network.label, mono = false)
            InfoRow("Chain", state.blockchain?.chain ?: "-", mono = false)
            InfoRow(
                "Best block",
                state.blockchain?.bestBlockHash?.let { shortHash(it) } ?: "-",
                copyValue = state.blockchain?.bestBlockHash?.ifBlank { null }
            )
            InfoRow("Difficulty", state.blockchain?.let { String.format(java.util.Locale.US, "%.3f T", it.difficulty / 1_000_000_000_000.0) } ?: "-")
            InfoRow("Protocol", state.network?.protocolVersion?.toString() ?: "-")
            InfoRow("Subversion", state.network?.subversion ?: "-")
            InfoRow("bitcoind", state.binaryVersion.ifBlank { "bundled Core" }, mono = false)
            if (state.pid != null) InfoRow("Process id", state.pid.toString())
        }

        SpacerH(24)
    }

    pendingBan?.let { peer ->
        AlertDialog(
            onDismissRequest = { pendingBan = null },
            containerColor = PocketColors.CardElevated,
            titleContentColor = PocketColors.Text,
            textContentColor = PocketColors.Muted,
            title = { Text("Ban this peer?") },
            text = {
                Text(
                    "${peer.host} will be banned for 24 hours and disconnected. " +
                        "The ban list is kept by this node only."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBan = null
                    expandedPeer = null
                    NodeController.banPeer(peer.address, peer.host)
                }) {
                    Text("BAN 24H", color = PocketColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBan = null }) {
                    Text("CANCEL", color = PocketColors.Muted)
                }
            }
        )
    }
}

@Composable
private fun PeerRow(
    peer: PeerInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDisconnect: () -> Unit,
    onBan: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = peer.flag, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = peer.address,
                    color = PocketColors.Text,
                    fontSize = 11.sp,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SpacerH(2)
                Text(
                    text = peer.userAgent.ifBlank { "unknown agent" },
                    color = PocketColors.Muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (peer.inbound) "IN" else "OUT",
                    color = if (peer.inbound) PocketColors.Muted else PocketColors.Accent,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                SpacerH(2)
                Text(
                    text = "${peer.pingMs.toInt()} ms",
                    color = PocketColors.Muted,
                    fontSize = 9.sp,
                    fontFamily = Mono
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 23.dp, bottom = 10.dp)) {
                InfoRow("Country", "${peer.flag} ${peer.countryName}", mono = false)
                InfoRow("Services", peer.services.ifBlank { "-" }, mono = true)
                InfoRow("Version", if (peer.version > 0) peer.version.toString() else "-")
                InfoRow("Received", formatBytes(peer.bytesReceived))
                InfoRow("Sent", formatBytes(peer.bytesSent))
                InfoRow("Connected", formatRelative(peer.connectionSeconds))
                if (peer.permissions.isNotBlank()) InfoRow("Permissions", peer.permissions)
                SpacerH(8)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        "DISCONNECT",
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f)
                    )
                    ActionButton(
                        "BAN 24H",
                        onClick = onBan,
                        modifier = Modifier.weight(1f),
                        tint = PocketColors.Error
                    )
                }
            }
        }
        Divider()
    }
}

@Composable
private fun HeroCard(state: NodeUiState) {
    val chain = state.blockchain
    val syncing = state.isSyncing
    CardBox(background = PocketColors.Card) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Block height", Modifier.weight(1f))
            Text(
                text = when {
                    state.status == NodeStatus.ERROR -> "ERROR"
                    state.status == NodeStatus.STOPPED -> "IDLE"
                    syncing -> "IBD"
                    else -> "SYNCED"
                },
                color = when {
                    state.status == NodeStatus.ERROR -> PocketColors.Error
                    syncing -> PocketColors.Accent
                    state.status == NodeStatus.RUNNING -> PocketColors.Running
                    else -> PocketColors.Faint
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
        SpacerH(10)
        Text(
            text = if (chain != null) formatCount(chain.blocks) else "—",
            color = PocketColors.Text,
            fontSize = 42.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        SpacerH(2)
        Text(
            text = if (chain != null) {
                "of ${formatCount(chain.headers)} headers · ${state.network?.connections ?: 0} peers"
            } else {
                "No chain data yet"
            },
            color = PocketColors.Muted,
            fontSize = 11.sp
        )
        SpacerH(14)
        ProgressLine(state.progress)
        SpacerH(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatPercent(state.progress),
                color = PocketColors.Text,
                fontSize = 11.sp,
                fontFamily = Mono
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (syncing) "verifying blocks" else if (state.status == NodeStatus.RUNNING) "fully validated view" else "waiting",
                color = PocketColors.Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            if (chain != null) {
                Text(
                    text = formatBytes(chain.sizeOnDisk),
                    color = PocketColors.Muted,
                    fontSize = 11.sp,
                    fontFamily = Mono
                )
            }
        }
        if (state.status == NodeStatus.WAITING) {
            SpacerH(10)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PocketColors.CardElevated)
                    .padding(10.dp)
            ) {
                Text(
                    text = (state.detail ?: "Waiting") + " · the node starts automatically when the condition is met",
                    color = PocketColors.Warning,
                    fontSize = 11.sp
                )
            }
        }
        if (state.status == NodeStatus.ERROR && state.detail != null) {
            SpacerH(10)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PocketColors.CardElevated)
                    .padding(10.dp)
            ) {
                Text(text = state.detail, color = PocketColors.Error, fontSize = 11.sp)
            }
        }
        if (state.snapshotActive) {
            SpacerH(10)
            Text(
                text = "AssumeUTXO snapshot active · background validation running",
                color = PocketColors.Accent,
                fontSize = 11.sp
            )
        }
    }
}
