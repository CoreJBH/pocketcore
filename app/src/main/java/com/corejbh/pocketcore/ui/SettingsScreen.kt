package com.corejbh.pocketcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.corejbh.pocketcore.BuildConfig
import com.corejbh.pocketcore.core.BitcoinNetwork
import com.corejbh.pocketcore.core.DescriptorScanController
import com.corejbh.pocketcore.core.DescriptorState
import com.corejbh.pocketcore.core.NodeController
import com.corejbh.pocketcore.core.NodePaths
import com.corejbh.pocketcore.core.NodeSettingsState
import com.corejbh.pocketcore.core.NodeStatus
import com.corejbh.pocketcore.core.NodeUiState
import com.corejbh.pocketcore.core.SystemState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    state: NodeUiState,
    config: NodeSettingsState,
    onNetworkChange: (BitcoinNetwork) -> Unit,
    onPruneChange: (Int) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onChargingOnlyChange: (Boolean) -> Unit,
    onTorProxyChange: (Boolean) -> Unit,
    onRebuildAsFull: () -> Unit,
    onWipeData: () -> Unit
) {
    val context = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var dataSize by remember { mutableStateOf(-1L) }
    var freeBytes by remember { mutableStateOf(-1L) }
    var confirmFull by remember { mutableStateOf(false) }
    val scan by DescriptorScanController.state.collectAsStateWithLifecycle()

    LaunchedEffect(config.network) {
        val measured = withContext(Dispatchers.IO) {
            NodePaths.datadirSizeBytes(context) to NodePaths.freeSpaceBytes(context)
        }
        dataSize = measured.first
        freeBytes = measured.second
    }
    LaunchedEffect(config.descriptorInput, config.descriptorNormalized) {
        DescriptorScanController.loadSaved(config.descriptorInput, config.descriptorNormalized)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        ScreenHeader(title = "Settings", subtitle = "Node, wallets, storage and background policy")
        SpacerH(12)

        CardBox {
            SectionLabel("Network")
            SpacerH(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BitcoinNetwork.entries.forEach { network ->
                    Chip(
                        text = network.label,
                        selected = config.network == network,
                        onClick = { onNetworkChange(network) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            SpacerH(10)
            Muted(
                "Mainnet is the real chain. Signet and Testnet4 are public test networks with their own data. " +
                    "Changing the network restarts the node; each chain keeps its own data directory."
            )
        }

        SpacerH(12)
        DescriptorCard(scan, state)

        SpacerH(12)
        CardBox {
            SectionLabel("Storage")
            SpacerH(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 0 means no pruning at all: the full chain is kept.
                listOf(550, 2048, 5120, 0).forEach { value ->
                    Chip(
                        text = when {
                            value == 0 -> "Full"
                            value < 1024 -> "$value MB"
                            else -> "${value / 1024} GB"
                        },
                        selected = config.pruneMb == value,
                        onClick = {
                            // Once a block file has been deleted Core will not
                            // re-open the chain as unpruned, so Full on a chain
                            // that already outgrew its prune target needs the
                            // data rebuilt first.
                            val prunedAlready = config.pruneMb != 0 &&
                                dataSize >= config.pruneMb.toLong() * 1024L * 1024L
                            if (value == 0 && prunedAlready) confirmFull = true
                            else onPruneChange(value)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            SpacerH(10)
            if (config.pruneMb == 0) {
                Muted(
                    "Full keeps every block, so the whole chain has to fit on the phone: about " +
                        "750-800 GB on mainnet, and it has to stay free while the chain is verified."
                )
                if (config.network == BitcoinNetwork.MAINNET && freeBytes >= 0 && freeBytes < FULL_CHAIN_BYTES) {
                    SpacerH(8)
                    NoteBanner(
                        "This phone has ${formatBytes(freeBytes)} free and a full mainnet chain needs " +
                            "about 800 GB. Pick a pruned size unless that storage is really there.",
                        PocketColors.Warning
                    )
                }
            } else {
                Muted("The chain is pruned to this size. A pruned node still validates every block and serves wallets normally.")
            }
            SpacerH(10)
            InfoRow("Data directory", NodePaths.dataDir(context).absolutePath, mono = false, truncate = true)
            InfoRow("Used by chain", if (dataSize >= 0) formatBytes(dataSize) else "calculating...")
            InfoRow("bitcoin.conf", NodePaths.confFile(context).absolutePath, mono = false, truncate = true)
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Background policy")
            SpacerH(6)
            ToggleRow(
                title = "Wi-Fi only",
                description = "Pause the node on mobile data and resume it when Wi-Fi returns.",
                checked = config.wifiOnly,
                onCheckedChange = onWifiOnlyChange
            )
            Divider()
            ToggleRow(
                title = "Charging only",
                description = "Run the node only while the phone is plugged in.",
                checked = config.chargingOnly,
                onCheckedChange = onChargingOnlyChange
            )
            SpacerH(8)
            val charging = SystemState.isCharging(context)
            InfoRow("Connection", SystemState.networkLabel(context), mono = false)
            InfoRow("Power", if (charging) "Charging" else "On battery", mono = false)
            SpacerH(6)
            Muted(
                "Pausing sends a graceful stop RPC so the chain state on disk stays consistent.",
                color = PocketColors.Faint
            )
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Privacy")
            SpacerH(6)
            ToggleRow(
                title = "Route through Tor (Orbot)",
                description = "Send peer connections through Orbot's SOCKS5 proxy at 127.0.0.1:9050, " +
                    "so no peer learns this phone's address. Orbot has to be running; the node " +
                    "restarts when this changes.",
                checked = config.torProxy,
                onCheckedChange = onTorProxyChange
            )
            SpacerH(8)
            Muted(
                "The node's RPC stays on 127.0.0.1 and is never proxied. With Tor on, connecting and " +
                    "downloading blocks run through the proxy and are slower than a direct connection.",
                color = PocketColors.Faint
            )
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Diagnostics")
            SpacerH(8)
            Muted(
                "bitcoind writes its detailed startup messages to debug.log. Open it when the node " +
                    "reports an error and you want the exact line Core wrote.",
                color = PocketColors.Muted
            )
            SpacerH(10)
            ActionButton(
                "VIEW DEBUG LOG",
                onClick = { showLog = true },
                modifier = Modifier.fillMaxWidth()
            )
            SpacerH(8)
            InfoRow("Log file", NodeController.debugLogPath(), mono = true, truncate = true)
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Danger zone")
            SpacerH(8)
            Muted("Deleting node data removes the chain, the peer cache and the log. Settings, RPC credentials and your descriptor are kept.")
            SpacerH(12)
            ActionButton(
                "DELETE NODE DATA",
                onClick = { confirmWipe = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.status != NodeStatus.STOPPING,
                tint = PocketColors.Error
            )
        }

        SpacerH(12)
        CardBox {
            SectionLabel("About")
            SpacerH(8)
            InfoRow("App", "PocketCore ${BuildConfig.VERSION_NAME}", mono = false)
            InfoRow("Package", BuildConfig.APPLICATION_ID, mono = true, truncate = true)
            InfoRow("bitcoind", state.binaryVersion.ifBlank { "bundled Bitcoin Core" }, mono = false)
            InfoRow("Binary", "libbitcoind.so (arm64-v8a)", mono = true)
            SpacerH(6)
            Muted(
                "Peer country flags use the free DB-IP country lite database bundled inside this app " +
                    "(CC BY 4.0, https://db-ip.com). No lookup leaves the phone."
            )
            SpacerH(6)
            Muted("A wallet-free Bitcoin Core full node. Keys stay in your own wallet; this app validates the chain.")
        }

        SpacerH(24)
    }

    if (confirmFull) {
        AlertDialog(
            onDismissRequest = { confirmFull = false },
            containerColor = PocketColors.CardElevated,
            titleContentColor = PocketColors.Text,
            textContentColor = PocketColors.Muted,
            title = { Text("Keep every block?") },
            text = {
                Text(
                    "This chain was built with pruning, and Bitcoin Core will not re-open it as a " +
                        "full node. Choosing Full deletes the chain data on this phone and downloads " +
                        "the whole chain again - about 750-800 GB of storage and a long download."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFull = false
                    onRebuildAsFull()
                }) {
                    Text("REBUILD AS FULL", color = PocketColors.Warning, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFull = false }) {
                    Text("KEEP PRUNED", color = PocketColors.Muted)
                }
            }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = PocketColors.CardElevated,
            titleContentColor = PocketColors.Text,
            textContentColor = PocketColors.Muted,
            title = { Text("Delete node data?") },
            text = { Text("The whole chain and its logs are deleted from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    onWipeData()
                }) {
                    Text("DELETE", color = PocketColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("CANCEL", color = PocketColors.Muted)
                }
            }
        )
    }

    if (showLog) {
        DebugLogDialog(onDismiss = { showLog = false })
    }
}

@Composable
private fun DescriptorCard(scan: DescriptorState, state: NodeUiState) {
    val context = LocalContext.current
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Watch-only descriptor", Modifier.weight(1f))
            Text(
                text = "NO KEYS",
                color = PocketColors.Running,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
        SpacerH(10)

        val saved = scan.normalized?.takeIf { it.isNotBlank() }
        if (saved != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PocketColors.CardElevated)
                    .padding(10.dp)
                    .clickable { copyToClipboard(context, "Descriptor", saved, isSensitive = true) }
            ) {
                Text(
                    text = saved,
                    color = PocketColors.Muted,
                    fontSize = 9.sp,
                    fontFamily = Mono,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SpacerH(10)
        }

        MonoField(
            value = scan.input,
            onValueChange = { DescriptorScanController.setInput(it) },
            placeholder = "wpkh(xpub.../0/*)  ·  tr(xpub.../0/*)  ·  xpub / zpub / ypub",
            singleLine = false,
            minLines = 3,
            maxLines = 5,
            enabled = !scan.busy
        )
        SpacerH(4)
        Muted(
            "Accepts public keys (xpub, zpub, ypub) or a full descriptor. " +
                "Private keys are not supported and are rejected before anything is sent to the node.",
            color = PocketColors.Faint
        )

        SpacerH(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                if (scan.busy) "SCANNING..." else "SCAN UTXO SET",
                onClick = { DescriptorScanController.scan() },
                enabled = !scan.busy && state.status == NodeStatus.RUNNING,
                filled = true,
                modifier = Modifier.weight(1f)
            )
            if (scan.busy) {
                ActionButton(
                    "CANCEL",
                    onClick = { DescriptorScanController.abort() },
                    modifier = Modifier.weight(1f),
                    tint = PocketColors.Error
                )
            } else if (scan.result != null || scan.error != null) {
                ActionButton(
                    "CLEAR",
                    onClick = { DescriptorScanController.clearResult() },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (scan.busy) {
            SpacerH(10)
            ProgressLine(scan.progress)
            SpacerH(6)
            Text(
                text = "${formatPercent(scan.progress)} of the UTXO set",
                color = PocketColors.Muted,
                fontSize = 10.sp,
                fontFamily = Mono
            )
        }

        scan.error?.let { error ->
            SpacerH(10)
            NoteBanner(error, PocketColors.Error)
        }

        scan.note?.let { note ->
            SpacerH(10)
            NoteBanner(note, PocketColors.Accent)
        }

        scan.result?.let { result ->
            SpacerH(14)
            Divider()
            SpacerH(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = "Scanned balance", color = PocketColors.Muted, fontSize = 10.sp)
                    SpacerH(3)
                    Text(
                        text = formatBtc(result.totalAmount),
                        color = PocketColors.Text,
                        fontSize = 22.sp,
                        fontFamily = Mono
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "${result.utxos.size} UTXO" + (if (result.utxos.size == 1) "" else "s"), color = PocketColors.Muted, fontSize = 10.sp)
                    SpacerH(3)
                    Text(
                        text = "at block ${formatCount(result.height)}",
                        color = PocketColors.Muted,
                        fontSize = 10.sp,
                        fontFamily = Mono
                    )
                }
            }
            SpacerH(10)
            if (result.utxos.isEmpty()) {
                EmptyNote("No unspent output of this descriptor is in the current UTXO set.")
            } else {
                result.utxos.take(10).forEach { utxo ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = utxo.address(state.config.network) ?: "no address form",
                                color = PocketColors.Text,
                                fontSize = 10.sp,
                                fontFamily = Mono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${shortHash(utxo.txid)}:${utxo.vout}",
                                color = PocketColors.Faint,
                                fontSize = 9.sp,
                                fontFamily = Mono,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatBtc(utxo.amount, withUnit = false),
                            color = PocketColors.Text,
                            fontSize = 10.sp,
                            fontFamily = Mono
                        )
                    }
                    Divider()
                }
                if (result.utxos.size > 10) {
                    SpacerH(4)
                    Muted("and ${result.utxos.size - 10} more unspent outputs")
                }
            }
            SpacerH(10)
            Muted(
                "The scan reads the confirmed UTXO set with scantxoutset. The node keeps no wallet " +
                    "database and never sees a private key.",
                color = PocketColors.Faint
            )
        }
    }
}

@Composable
private fun DebugLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        log = withContext(Dispatchers.IO) { NodeController.debugLog(400) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PocketColors.CardElevated,
        titleContentColor = PocketColors.Text,
        textContentColor = PocketColors.Muted,
        title = { Text("Debug log") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (log.isBlank()) "No log lines yet." else log,
                    color = PocketColors.Muted,
                    fontSize = 9.sp,
                    fontFamily = Mono,
                    lineHeight = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, "debug.log", log, isSensitive = true)
            }) {
                Text("COPY LOG", color = PocketColors.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = PocketColors.Muted)
            }
        }
    )
}

/** Rough size of the full mainnet chain, used for the Full storage warning. */
private const val FULL_CHAIN_BYTES = 800L * 1024L * 1024L * 1024L
