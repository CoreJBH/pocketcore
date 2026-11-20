package com.jbitmstr.pocketcore.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.pocketcore.core.NodeConfig
import com.jbitmstr.pocketcore.core.NodeController
import com.jbitmstr.pocketcore.core.NodeStatus
import com.jbitmstr.pocketcore.core.NodeUiState
import com.jbitmstr.pocketcore.core.PeerInfo

@Composable
fun NodeScreen(
    state: NodeUiState,
    onStart: (Boolean) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenSync: () -> Unit
) {
    val context = LocalContext.current
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
                    text = "POCKETCORE",
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
            when (state.status) {
                NodeStatus.RUNNING, NodeStatus.STARTING -> {
                    ActionButton("STOP", onStop, modifier = Modifier.weight(1f))
                    ActionButton("RESTART", onRestart, modifier = Modifier.weight(1f))
                }
                NodeStatus.WAITING -> {
                    ActionButton("START NOW", onClick = { onStart(true) }, filled = true, modifier = Modifier.weight(2f))
                    ActionButton("STOP", onStop, modifier = Modifier.weight(1f))
                }
                NodeStatus.STOPPING -> {
                    ActionButton("STOPPING", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                }
                NodeStatus.ERROR, NodeStatus.STOPPED -> {
                    ActionButton("START NODE", onClick = { onStart(false) }, filled = true, modifier = Modifier.weight(2f))
                    ActionButton("SYNC", onOpenSync, modifier = Modifier.weight(1f))
                }
            }
        }

        if (state.detail != null && state.status != NodeStatus.RUNNING) {
            SpacerH(10)
            Muted(state.detail, color = if (state.status == NodeStatus.ERROR) PocketColors.Error else PocketColors.Muted, size = 11)
        }

        state.portNote?.let { note ->
            SpacerH(10)
            NoteBanner(note, PocketColors.Warning)
        }

        SpacerH(16)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Peers",
                value = state.network?.connections?.toString() ?: "0",
                sub = state.network?.let { "${it.connectionsIn} in / ${it.connectionsOut} out" },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Mempool",
                value = state.mempool?.let { formatCount(it.transactions.toLong()) } ?: "0",
                sub = state.mempool?.let { "${formatBytes(it.bytes)} / ${formatBytes(it.usage)} usage" },
                modifier = Modifier.weight(1f)
            )
        }
        SpacerH(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Chain data",
                value = state.blockchain?.let { formatBytes(it.sizeOnDisk) } ?: "-",
                sub = state.blockchain?.let {
                    if (it.pruned) "pruned at ${formatCount(it.pruneHeight)}" else "full chain"
                },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Uptime",
                value = state.uptimeSeconds?.let { formatDuration(it) } ?: "-",
                sub = state.networkLabel.ifBlank { null },
                modifier = Modifier.weight(1f)
            )
        }

        SpacerH(16)
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Local JSON-RPC", Modifier.weight(1f))
                Text(
                    text = if (state.rpcReachable) "ONLINE" else "OFFLINE",
                    color = if (state.rpcReachable) PocketColors.Running else PocketColors.Faint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
            SpacerH(8)
            InfoRow("Host", "127.0.0.1")
            InfoRow(
                "Port",
                if (state.customRpcPort) "${state.rpcPort} (${state.config.network.rpcPort} was busy)" else state.rpcPort.toString(),
                copyValue = state.rpcPort.toString()
            )
            InfoRow("User", state.config.rpcUser, copyValue = state.config.rpcUser)
            var revealed by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow(
                    label = "Password",
                    value = if (revealed) state.config.rpcPassword else "••••••••••••",
                    copyValue = state.config.rpcPassword,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (revealed) "HIDE" else "SHOW",
                    color = PocketColors.Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { revealed = !revealed }
                )
            }
            SpacerH(6)
            Muted("Loopback only. Wallets and tools on this phone can connect to this endpoint.")
            SpacerH(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    "COPY CONNECTION",
                    onClick = {
                        copyToClipboard(context, "RPC connection", NodeConfig.rpcSnippet(state.config, state.rpcPort))
                    },
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    "COPY WALLET CONF",
                    onClick = {
                        copyToClipboard(context, "bitcoin.conf", NodeConfig.clientConf(state.config, state.rpcPort))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
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
                state.peers.take(6).forEach { peer ->
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
                if (state.peers.size > 6) {
                    SpacerH(4)
                    Muted("and ${state.peers.size - 6} more connected peers")
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
