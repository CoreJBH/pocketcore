package com.corejbh.pocketcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.corejbh.pocketcore.core.BlockInfo
import com.corejbh.pocketcore.core.DescriptorScanController
import com.corejbh.pocketcore.core.DescriptorState
import com.corejbh.pocketcore.core.ExplorerController
import com.corejbh.pocketcore.core.ExplorerState
import com.corejbh.pocketcore.core.NodeController
import com.corejbh.pocketcore.core.NodeStatus
import com.corejbh.pocketcore.core.NodeUiState
import com.corejbh.pocketcore.core.RawTransaction
import com.corejbh.pocketcore.core.ScanResult
import com.corejbh.pocketcore.core.TxSummary

@Composable
fun ExploreScreen(state: NodeUiState, onOpenSettings: () -> Unit) {
    val explorer by ExplorerController.state.collectAsStateWithLifecycle()
    val scan by DescriptorScanController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        ScreenHeader(
            title = "Explore",
            subtitle = "Blocks and transactions, read from this phone's own node"
        )
        SpacerH(12)

        ChainStatusCard(state, onLatestBlock = { ExplorerController.openLatestBlock() })

        if (state.status != NodeStatus.RUNNING) {
            SpacerH(10)
            NoteBanner(
                "The node is not running. Lookups work as soon as START NODE reports running.",
                PocketColors.Warning
            )
        }

        SpacerH(12)
        BlockLookupCard(explorer, context)
        SpacerH(12)
        TransactionCard(explorer, context)
        SpacerH(12)
        BroadcastCard(explorer, context)
        scan.result?.let { result ->
            SpacerH(12)
            WatchOnlySummary(scan, result, state, onOpenSettings)
        }

        SpacerH(24)
    }
}

@Composable
private fun ChainStatusCard(state: NodeUiState, onLatestBlock: () -> Unit) {
    val chain = state.blockchain
    val context = LocalContext.current
    CardBox(background = PocketColors.CardElevated) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Chain status", Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chain?.pruned == true) PocketColors.Card else PocketColors.Accent.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (chain?.pruned == true) "PRUNED" else "FULL",
                    color = if (chain?.pruned == true) PocketColors.Muted else PocketColors.Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
        }
        SpacerH(8)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = chain?.let { formatCount(it.blocks) } ?: "—",
                color = PocketColors.Text,
                fontSize = 32.sp,
                fontFamily = Mono,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            SpacerH(0)
            Text(
                text = if (chain != null) "  of ${formatCount(chain.headers)} headers" else "",
                color = PocketColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        SpacerH(6)
        Row {
            Column(Modifier.weight(1f)) {
                Text(text = "Validated blocks", color = PocketColors.Muted, fontSize = 10.sp)
                Text(
                    text = chain?.let { formatCount(it.blocks) } ?: "-",
                    color = PocketColors.Text,
                    fontSize = 12.sp,
                    fontFamily = Mono
                )
            }
            Column(Modifier.weight(1f)) {
                Text(text = "Difficulty", color = PocketColors.Muted, fontSize = 10.sp)
                Text(
                    text = chain?.let { String.format(java.util.Locale.US, "%.2f T", it.difficulty / 1_000_000_000_000.0) } ?: "-",
                    color = PocketColors.Text,
                    fontSize = 12.sp,
                    fontFamily = Mono
                )
            }
        }
        SpacerH(8)
        Text(text = "Best block", color = PocketColors.Muted, fontSize = 10.sp)
        SpacerH(3)
        Text(
            text = chain?.bestBlockHash?.takeIf { it.isNotBlank() }?.let { middleHash(it, 20, 8) } ?: "No best block yet",
            color = PocketColors.Text,
            fontSize = 10.sp,
            fontFamily = Mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(enabled = chain?.bestBlockHash?.isNotBlank() == true) {
                chain?.bestBlockHash?.let { copyToClipboard(context, "Best block", it) }
            }
        )
        SpacerH(12)
        ActionButton(
            "VIEW LATEST BLOCK",
            onClick = onLatestBlock,
            filled = true,
            enabled = state.status == NodeStatus.RUNNING,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BlockLookupCard(explorer: ExplorerState, context: android.content.Context) {
    CardBox {
        SectionLabel("Block lookup")
        SpacerH(10)
        MonoField(
            value = explorer.blockQuery,
            onValueChange = { ExplorerController.setBlockQuery(it) },
            placeholder = "840000  \u00b7  000000000019d668...",
            singleLine = true
        )
        SpacerH(10)
        ActionButton(
            if (explorer.blockBusy) "READING..." else "LOOK UP BLOCK",
            onClick = { ExplorerController.lookupBlock() },
            enabled = !explorer.blockBusy,
            filled = true,
            modifier = Modifier.fillMaxWidth()
        )
        explorer.blockError?.let { error ->
            SpacerH(10)
            NoteBanner(error, PocketColors.Error)
        }
        explorer.blockNote?.let { note ->
            SpacerH(10)
            NoteBanner(note, PocketColors.Accent)
        }
        explorer.block?.let { block ->
            SpacerH(14)
            Divider()
            SpacerH(12)
            BlockDetail(block, context)
        }
    }
}

@Composable
private fun BlockDetail(block: BlockInfo, context: android.content.Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Block #${formatCount(block.height)}",
            color = PocketColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (block.headerOnly) "HEADER ONLY" else "${formatCount(block.confirmations.toLong())} conf",
            color = if (block.headerOnly) PocketColors.Warning else PocketColors.Muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
    SpacerH(8)
    InfoRow("Hash", middleHash(block.hash), copyValue = block.hash)
    InfoRow("Time", formatDateTime(block.time), mono = false)
    InfoRow("Transactions", formatCount(block.txCount.toLong()))
    block.size?.let { InfoRow("Size", formatBytes(it)) }
    block.weight?.let { InfoRow("Weight", formatCount(it)) }
    block.strippedSize?.let { InfoRow("Stripped size", formatBytes(it)) }
    InfoRow("Version", block.version.toString())
    InfoRow("Nonce", formatCount(block.nonce))
    InfoRow("Bits", block.bits)
    InfoRow("Difficulty", String.format(java.util.Locale.US, "%.3f T", block.difficulty / 1_000_000_000_000.0))
    InfoRow("Merkle root", middleHash(block.merkleRoot, 12, 8), copyValue = block.merkleRoot)
    block.previousHash?.let { InfoRow("Previous block", middleHash(it, 10, 6), copyValue = it) }
    block.nextHash?.let { InfoRow("Next block", middleHash(it, 10, 6), copyValue = it) }

    if (block.headerOnly) {
        SpacerH(8)
        NoteBanner(
            "Block body pruned (header verified). The block index still proves this header is part of " +
                "the validated chain.",
            PocketColors.Warning
        )
    } else if (block.transactions.isNotEmpty()) {
        SpacerH(12)
        SectionLabel("Transactions")
        SpacerH(4)
        block.transactions.take(12).forEach { transaction ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        ExplorerController.setTxQuery(transaction.txid)
                        ExplorerController.lookupTransaction(transaction.txid, block.hash)
                    }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = shortHash(transaction.txid),
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatBtc(transaction.totalOut, withUnit = false),
                    color = PocketColors.Muted,
                    fontSize = 10.sp,
                    fontFamily = Mono
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "\u2192", color = PocketColors.Accent, fontSize = 12.sp)
            }
            Divider()
        }
        if (block.transactions.size > 12) {
            SpacerH(6)
            Muted("and ${block.transactions.size - 12} more transactions in this block")
        }
    }
}

@Composable
private fun TransactionCard(explorer: ExplorerState, context: android.content.Context) {
    CardBox {
        SectionLabel("Transaction lookup")
        SpacerH(10)
        MonoField(
            value = explorer.txQuery,
            onValueChange = { ExplorerController.setTxQuery(it) },
            placeholder = "4a5e1e4baab89f3a...  (64-char txid)",
            singleLine = true,
            showCounter = true
        )
        SpacerH(10)
        ActionButton(
            if (explorer.txBusy) "READING..." else "LOOK UP TRANSACTION",
            onClick = { ExplorerController.lookupTransaction() },
            enabled = !explorer.txBusy,
            filled = true,
            modifier = Modifier.fillMaxWidth()
        )
        explorer.txError?.let { error ->
            SpacerH(10)
            NoteBanner(error, PocketColors.Error)
        }
        explorer.txNote?.let { note ->
            SpacerH(10)
            NoteBanner(note, PocketColors.Accent)
        }
        explorer.tx?.let { transaction ->
            SpacerH(14)
            Divider()
            SpacerH(12)
            TransactionDetail(transaction, context)
        }
        SpacerH(10)
        Muted(
            "A wallet-free node keeps no transaction index, so confirmed transactions are found " +
                "through their block. Tap a transaction in a block above to open it here.",
            color = PocketColors.Faint
        )
    }
}

@Composable
private fun TransactionDetail(transaction: TxSummary, context: android.content.Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Transaction",
            color = PocketColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (transaction.inMempool) "MEMPOOL" else "${formatCount(transaction.confirmations)} conf",
            color = if (transaction.inMempool) PocketColors.Warning else PocketColors.Running,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
    SpacerH(8)
    InfoRow("Txid", middleHash(transaction.txid, 12, 8), copyValue = transaction.txid)
    InfoRow("Size", "${formatCount(transaction.size)} B")
    InfoRow("Virtual size", "${formatCount(transaction.vsize)} vB")
    InfoRow("Weight", formatCount(transaction.weight))
    InfoRow("Version", transaction.version.toString())
    InfoRow("Locktime", transaction.locktime.toString())
    transaction.blockTime?.let { InfoRow("Block time", formatDateTime(it), mono = false) }
    InfoRow("Outputs total", formatBtc(transaction.totalOut))
    transaction.fee?.let { InfoRow("Fee", "${formatBtc(it)} (${formatSats(it)})") }

    SpacerH(12)
    SectionLabel("Inputs (${transaction.inputCount})")
    SpacerH(4)
    transaction.inputs.take(8).forEach { input ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (input.coinbase) "Coinbase (newly minted coins)" else shortHash(input.prevTxid),
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    fontStyle = if (input.coinbase) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = input.address ?: "unknown address",
                    color = PocketColors.Muted,
                    fontSize = 9.sp,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = input.value?.let { formatBtc(it, withUnit = false) } ?: "-",
                color = PocketColors.Muted,
                fontSize = 10.sp,
                fontFamily = Mono
            )
        }
        Divider()
    }
    if (transaction.inputCount > 8) {
        SpacerH(4)
        Muted("and ${transaction.inputCount - 8} more inputs")
    }

    SpacerH(10)
    SectionLabel("Outputs (${transaction.outputCount})")
    SpacerH(4)
    transaction.outputs.take(8).forEach { output ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = output.address ?: "non-standard script",
                    color = PocketColors.Text,
                    fontSize = 10.sp,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (output.address != null) {
                        Modifier.clickable { copyToClipboard(context, "Address", output.address!!) }
                    } else {
                        Modifier
                    }
                )
                Text(
                    text = "#${output.index}${output.type?.let { " \u00b7 $it" } ?: ""}",
                    color = PocketColors.Faint,
                    fontSize = 9.sp
                )
            }
            Text(
                text = formatBtc(output.value, withUnit = false),
                color = PocketColors.Text,
                fontSize = 10.sp,
                fontFamily = Mono
            )
        }
        Divider()
    }
    if (transaction.outputCount > 8) {
        SpacerH(4)
        Muted("and ${transaction.outputCount - 8} more outputs")
    }
}

@Composable
private fun BroadcastCard(explorer: ExplorerState, context: android.content.Context) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "\u27a4", color = PocketColors.Accent, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            SectionLabel("Broadcast transaction")
        }
        SpacerH(10)
        MonoField(
            value = explorer.broadcastInput,
            onValueChange = { ExplorerController.setBroadcastInput(it) },
            placeholder = "020000000001...  (raw signed tx hex)",
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            showCounter = true
        )
        SpacerH(4)
        Muted(
            "Whitespace, line breaks and a 0x prefix are removed before sending. " +
                "The transaction must be fully signed.",
            color = PocketColors.Faint
        )
        SpacerH(10)
        val parsed = RawTransaction.parse(explorer.broadcastInput)
        val bytes = RawTransaction.byteLength(explorer.broadcastInput)
        ActionButton(
            if (explorer.broadcastBusy) "BROADCASTING..." else "BROADCAST",
            onClick = { ExplorerController.broadcast() },
            enabled = !explorer.broadcastBusy && parsed is RawTransaction.Parsed.Valid,
            filled = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (explorer.broadcastInput.isNotBlank()) {
            SpacerH(6)
            Muted(
                when (parsed) {
                    is RawTransaction.Parsed.Invalid -> parsed.reason
                    is RawTransaction.Parsed.Valid -> "$bytes bytes ready to send"
                },
                color = if (parsed is RawTransaction.Parsed.Invalid) PocketColors.Warning else PocketColors.Faint
            )
        }
        explorer.broadcastError?.let { error ->
            SpacerH(10)
            NoteBanner("The node rejected this transaction: $error", PocketColors.Error)
        }
        explorer.broadcastTxid?.let { txid ->
            SpacerH(10)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PocketColors.CardElevated)
                    .padding(12.dp)
            ) {
                Column {
                    Text(text = "Accepted by the node", color = PocketColors.Running, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    SpacerH(6)
                    Text(
                        text = txid,
                        color = PocketColors.Text,
                        fontSize = 10.sp,
                        fontFamily = Mono
                    )
                    SpacerH(8)
                    ActionButton(
                        "COPY TXID",
                        onClick = { copyToClipboard(context, "Transaction id", txid) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            explorer.broadcastNote?.let { note ->
                SpacerH(6)
                Muted(note, color = PocketColors.Muted)
            }
        }
    }
}

@Composable
private fun WatchOnlySummary(
    scan: DescriptorState,
    result: ScanResult,
    nodeState: NodeUiState,
    onOpenSettings: () -> Unit
) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Watch-only balance", Modifier.weight(1f))
            Text(
                text = "SCAN",
                color = PocketColors.Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
        SpacerH(8)
        Text(
            text = formatBtc(result.totalAmount),
            color = PocketColors.Text,
            fontSize = 24.sp,
            fontFamily = Mono
        )
        SpacerH(3)
        Text(
            text = "${result.utxos.size} unspent output" + (if (result.utxos.size == 1) "" else "s") +
                " at block ${formatCount(result.height)}",
            color = PocketColors.Muted,
            fontSize = 11.sp
        )
        if (result.utxos.isNotEmpty()) {
            SpacerH(10)
            result.utxos.take(4).forEach { utxo ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = utxo.address(nodeState.config.network) ?: shortHash(utxo.txid),
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
            if (result.utxos.size > 4) {
                SpacerH(4)
                Muted("and ${result.utxos.size - 4} more in Settings")
            }
        }
        SpacerH(10)
        ActionButton("DESCRIPTOR SETTINGS", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
        scan.note?.let { note ->
            SpacerH(8)
            Muted(note, color = PocketColors.Faint)
        }
    }
}

/** Keeps the "and N more" text aligned with the rest of the dense lists. */

