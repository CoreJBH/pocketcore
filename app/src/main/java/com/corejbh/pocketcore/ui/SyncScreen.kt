package com.corejbh.pocketcore.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corejbh.pocketcore.core.AssumeUtxo
import com.corejbh.pocketcore.core.BitcoinNetwork
import com.corejbh.pocketcore.core.ChainStateInfo
import com.corejbh.pocketcore.core.NodeStatus
import com.corejbh.pocketcore.core.NodeUiState

@Composable
fun SyncScreen(
    state: NodeUiState,
    onPickSnapshot: (Uri, String?) -> Unit,
    onLoadSnapshot: () -> Unit,
    onClearSnapshot: () -> Unit,
    onExportSnapshot: (Uri, String?) -> Unit,
    onClearExport: () -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri)
            onPickSnapshot(uri, name)
        }
    }
    // A snapshot written by this node: the name suggests what the file is, and
    // the user can still rename it in the system picker.
    val exportName = remember(state.config.network, state.blocks) {
        val day = java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        "pocketcore-${state.config.network.chainArg}-${state.blocks}-$day.dat"
    }
    val creator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) onExportSnapshot(uri, queryDisplayName(context, uri) ?: exportName)
    }
    // The newest official checkpoint of each network, for the LOAD note: those are
    // the heights a file from another phone has to be at (versionCode 20).
    val checkpoints = remember {
        BitcoinNetwork.entries.joinToString(", ") { network ->
            "${network.label.lowercase()} ${formatCount(AssumeUtxo.latest(network).height)}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        ScreenHeader(
            title = "Sync",
            subtitle = "Initial block download and AssumeUTXO snapshots"
        )
        SpacerH(12)

        CardBox {
            SectionLabel("Chain states")
            SpacerH(10)
            if (state.chainStates.isEmpty()) {
                EmptyNote("No chain state reported. Start the node to see sync progress.")
            } else {
                state.chainStates.forEachIndexed { index, chainState ->
                    if (index > 0) SpacerH(14)
                    val baseHeight = if (chainState.snapshot) {
                        state.chainStates.firstOrNull { !it.snapshot }?.blocks
                    } else {
                        null
                    }
                    ChainStateRow(chainState, baseHeight)
                }
            }
            SpacerH(12)
            ProgressLine(state.progress, if (state.blockchain?.initialBlockDownload == true) PocketColors.Accent else PocketColors.Running)
            SpacerH(8)
            Row {
                Text(
                    text = formatPercent(state.progress),
                    color = PocketColors.Text,
                    fontSize = 11.sp,
                    fontFamily = Mono
                )
                SpacerH(0)
                Text(
                    text = "  ·  ${formatCount(state.blocks)} / ${formatCount(state.headers)} blocks",
                    color = PocketColors.Muted,
                    fontSize = 11.sp,
                    fontFamily = Mono
                )
            }
        }

        SpacerH(12)
        CardBox {
            SectionLabel("AssumeUTXO snapshot")
            SpacerH(10)
            Muted(
                "A UTXO snapshot lets the node reach a recent height in minutes instead of downloading " +
                    "every block from genesis. Full validation still runs in the background, so the node " +
                    "ends up exactly as verified as a from-genesis sync."
            )
            SpacerH(12)

            val snapshot = state.snapshot
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketColors.CardElevated)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = snapshot.label ?: "No snapshot selected",
                        color = if (snapshot.path != null) PocketColors.Text else PocketColors.Muted,
                        fontSize = 12.sp,
                        fontFamily = if (snapshot.path != null) Mono else null,
                        maxLines = 1
                    )
                    SpacerH(3)
                    Text(
                        text = if (snapshot.path != null) formatBytes(snapshot.sizeBytes) else "Pick a .dat file dumped with dumptxoutset",
                        color = PocketColors.Muted,
                        fontSize = 10.sp
                    )
                }
            }

            SpacerH(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    "CHOOSE .DAT",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !snapshot.busy
                )
                ActionButton(
                    "LOAD SNAPSHOT",
                    onClick = onLoadSnapshot,
                    modifier = Modifier.weight(1f),
                    filled = true,
                    enabled = snapshot.path != null && !snapshot.busy && state.status == NodeStatus.RUNNING
                )
            }
            if (snapshot.path != null) {
                SpacerH(8)
                ActionButton(
                    "REMOVE FILE",
                    onClick = onClearSnapshot,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !snapshot.busy
                )
            }

            if (snapshot.busy) {
                SpacerH(12)
                Muted("Working on the snapshot file...", color = PocketColors.Accent, size = 11)
            }
            snapshot.result?.let { result ->
                SpacerH(12)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PocketColors.CardElevated)
                        .padding(10.dp)
                ) {
                    Text(text = result, color = PocketColors.Running, fontSize = 11.sp)
                }
            }
            snapshot.error?.let { error ->
                SpacerH(12)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PocketColors.CardElevated)
                        .padding(10.dp)
                ) {
                    Text(text = error, color = PocketColors.Error, fontSize = 11.sp)
                }
            }
            SpacerH(12)
            Muted(
                "The file must come from the same network, and a file made by another phone has to be " +
                    "one of Core's official checkpoints ($checkpoints): loadtxoutset compares the file " +
                    "with the checkpoints built into the daemon, so a dump taken at a node's own tip is " +
                    "accepted only by the phone that made it. On mainnet a snapshot can only be loaded " +
                    "while the node is below the snapshot height.",
                color = PocketColors.Faint
            )

            SpacerH(14)
            Divider()
            SpacerH(12)
            ExportSnapshotSection(
                state = state,
                onExport = { creator.launch(exportName) },
                onClearExport = onClearExport
            )
        }

        SpacerH(12)
        CardBox {
            SectionLabel("Storage")
            SpacerH(8)
            InfoRow("Prune target", "${formatCount(state.config.pruneMb.toLong())} MB", mono = true)
            InfoRow(
                "Chain data on disk",
                state.blockchain?.let { formatBytes(it.sizeOnDisk) } ?: "-"
            )
            InfoRow(
                "Prune height",
                state.blockchain?.let { if (it.pruned) formatCount(it.pruneHeight) else "not pruned" } ?: "-"
            )
            SpacerH(8)
            Muted("Pruning keeps the last ~${formatCount(state.config.pruneMb.toLong())} MB of blocks and validates everything else on the fly.")
        }

        SpacerH(24)
    }
}

/**
 * `dumptxoutset` for this node: writes the current UTXO set and hands it to the
 * file the user picked.
 *
 * Disabled unless the node is running and no snapshot is being imported or
 * exported, because Core is busy with the dump while it runs.
 */
@Composable
private fun ExportSnapshotSection(
    state: NodeUiState,
    onExport: () -> Unit,
    onClearExport: () -> Unit
) {
    val export = state.snapshotExport
    val checkpoint = AssumeUtxo.latest(state.config.network)
    SectionLabel("Export this node's snapshot")
    SpacerH(8)
    Muted(
        "Bitcoin Core writes a UTXO set to a .dat file (dumptxoutset), and the other phone starts " +
            "from LOAD SNAPSHOT instead of downloading every block from genesis. The dump is taken at " +
            "the newest official checkpoint of ${state.config.network.label} " +
            "(block ${formatCount(checkpoint.height)}), because loadtxoutset accepts only the " +
            "checkpoints compiled into Core: a file dumped at the tip loads on this phone alone. " +
            "The file is written inside the app first and copied to the one you pick; it never " +
            "leaves the phone except through that file."
    )
    SpacerH(10)
    ActionButton(
        if (export.busy) "EXPORTING..." else "EXPORT SNAPSHOT",
        onClick = onExport,
        modifier = Modifier.fillMaxWidth(),
        filled = true,
        enabled = state.status == NodeStatus.RUNNING && !state.snapshot.busy && !export.busy
    )

    if (export.busy) {
        SpacerH(10)
        Muted(export.stage ?: "Working...", color = PocketColors.Accent, size = 11)
        SpacerH(4)
        Muted(
            "The dump runs inside the node and takes minutes on a phone. The node stays online " +
                "and keeps validating while it works.",
            color = PocketColors.Faint
        )
    }

    export.dump?.let { dump ->
        SpacerH(12)
        InfoRow("Exported file", export.fileName ?: "-", mono = false, truncate = true)
        InfoRow("File size", export.bytesWritten?.let { formatBytes(it) } ?: "-")
        InfoRow("Base height", formatCount(dump.baseHeight), mono = true)
        InfoRow("Coins written", formatCount(dump.coinsWritten), mono = true)
        InfoRow("Transactions to base", formatCount(dump.chainTx), mono = true)
        InfoRow("UTXO set hash", shortHash(dump.txoutsetHash), mono = true, truncate = true)
        SpacerH(8)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PocketColors.CardElevated)
                .padding(10.dp)
        ) {
            Text(text = dump.summary(), color = PocketColors.Running, fontSize = 11.sp)
        }
        if (dump.baseHash.isNotEmpty()) {
            SpacerH(6)
            Muted("Base block ${shortHash(dump.baseHash)}", color = PocketColors.Faint)
        }
        export.checkpoint?.let { verifiedAgainst ->
            SpacerH(8)
            if (export.checkpointVerified == true) {
                NoteBanner(
                    "Official checkpoint ${formatCount(verifiedAgainst.height)}: the UTXO set hash " +
                        "matches the value Core carries, so another phone with PocketCore can LOAD this file.",
                    PocketColors.Running
                )
            } else {
                NoteBanner(
                    "The dump does not match the official checkpoint ${formatCount(verifiedAgainst.height)}. " +
                        "Another phone's LOAD SNAPSHOT will reject it; this file only loads on this phone.",
                    PocketColors.Warning
                )
            }
        }
        SpacerH(8)
        ActionButton("CLEAR EXPORT", onClick = onClearExport, modifier = Modifier.fillMaxWidth())
    }

    export.error?.let { error ->
        SpacerH(12)
        NoteBanner(error, PocketColors.Error)
    }
}

@Composable
private fun ChainStateRow(chainState: ChainStateInfo, baseHeight: Long?) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chainState.name,
                color = PocketColors.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatPercent(chainState.verificationProgress),
                color = PocketColors.Muted,
                fontSize = 11.sp,
                fontFamily = Mono
            )
        }
        SpacerH(6)
        ProgressLine(
            chainState.verificationProgress,
            if (chainState.snapshot) PocketColors.Accent else PocketColors.Running
        )
        SpacerH(5)
        Text(
            text = buildString {
                append("${formatCount(chainState.blocks)} blocks")
                if (baseHeight != null) append(" · base at ${formatCount(baseHeight)}")
                if (chainState.baseHash.isNotEmpty()) append(" · ${shortHash(chainState.baseHash)}")
            },
            color = PocketColors.Faint,
            fontSize = 10.sp,
            fontFamily = Mono
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
