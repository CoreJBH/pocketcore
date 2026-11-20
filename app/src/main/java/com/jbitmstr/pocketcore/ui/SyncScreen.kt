package com.jbitmstr.pocketcore.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbitmstr.pocketcore.core.ChainStateInfo
import com.jbitmstr.pocketcore.core.NodeStatus
import com.jbitmstr.pocketcore.core.NodeUiState

@Composable
fun SyncScreen(
    state: NodeUiState,
    onPickSnapshot: (Uri, String?) -> Unit,
    onLoadSnapshot: () -> Unit,
    onClearSnapshot: () -> Unit
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
                "The file must come from the same network. On mainnet a snapshot can only be loaded " +
                    "while the node is below the snapshot height.",
                color = PocketColors.Faint
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
