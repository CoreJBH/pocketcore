package com.corejbh.pocketcore.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the EXPLORE tab shows. */
data class ExplorerState(
    val blockQuery: String = "",
    val blockBusy: Boolean = false,
    val block: BlockInfo? = null,
    val blockError: String? = null,
    val blockNote: String? = null,
    val txQuery: String = "",
    val txBusy: Boolean = false,
    val tx: TxSummary? = null,
    val txError: String? = null,
    val txNote: String? = null,
    val broadcastInput: String = "",
    val broadcastBusy: Boolean = false,
    val broadcastTxid: String? = null,
    val broadcastError: String? = null,
    val broadcastNote: String? = null
)

/**
 * Block, transaction and broadcast lookups against the local node.
 *
 * Every answer comes from this phone's own bitcoind; nothing is sent to an
 * explorer service.
 */
object ExplorerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    // One lookup per area at a time: a second tap or a new query cancels the
    // previous request, so the answer that lands last is always the one asked
    // for last.
    private var blockJob: Job? = null
    private var txJob: Job? = null
    private var broadcastJob: Job? = null

    fun setBlockQuery(text: String) {
        _state.update { it.copy(blockQuery = text) }
    }

    fun setTxQuery(text: String) {
        _state.update { it.copy(txQuery = text) }
    }

    fun setBroadcastInput(text: String) {
        _state.update { it.copy(broadcastInput = text, broadcastError = null, broadcastTxid = null) }
    }

    fun clearBlock() {
        _state.update { it.copy(block = null, blockError = null, blockNote = null) }
    }

    fun clearTransaction() {
        _state.update { it.copy(tx = null, txError = null, txNote = null) }
    }

    fun openLatestBlock() {
        val hash = NodeController.state.value.blockchain?.bestBlockHash
        if (hash.isNullOrBlank()) {
            _state.update { it.copy(blockError = "The node has no best block yet. Wait for a header first.") }
            return
        }
        _state.update { it.copy(blockQuery = hash) }
        lookupBlock(hash)
    }

    fun lookupBlock(query: String = _state.value.blockQuery) {
        val text = query.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(blockError = "Enter a block height or a block hash.", block = null) }
            return
        }
        val client = NodeController.client()
        if (client == null) {
            _state.update { it.copy(blockError = "Start the node before looking up blocks.", block = null) }
            return
        }
        blockJob?.cancel()
        blockJob = scope.launch {
            _state.update { it.copy(blockBusy = true, blockError = null, blockNote = null, blockQuery = text) }
            try {
                val hash = when {
                    text.all(Char::isDigit) -> {
                        val height = text.toLongOrNull()
                            ?: throw RpcException(0, "That height is too large.")
                        client.blockHash(height)
                    }
                    else -> {
                        val candidate = text.lowercase()
                        if (candidate.length != 64 || !RawTransaction.isHex(candidate)) {
                            throw RpcException(
                                0,
                                "A block hash is 64 hexadecimal characters. Enter a height (for example 976721) " +
                                    "or a full hash."
                            )
                        }
                        candidate
                    }
                }
                val block = try {
                    BlockInfo.fromBlock(client.block(hash))
                } catch (error: RpcException) {
                    if (isPruned(error)) {
                        val header = BlockInfo.fromHeader(client.blockHeader(hash))
                        _state.update {
                            it.copy(
                                block = header,
                                blockBusy = false,
                                blockError = null,
                                blockNote = "Block body pruned (header verified). This node keeps only the last " +
                                    "part of the chain, so the header is shown from the validated block index."
                            )
                        }
                        return@launch
                    }
                    throw error
                }
                _state.update {
                    it.copy(
                        block = block,
                        blockBusy = false,
                        blockError = null,
                        blockNote = if (block.transactions.isEmpty()) "This block has no transactions in its body." else null
                    )
                }
            } catch (error: RpcException) {
                _state.update {
                    it.copy(block = null, blockBusy = false, blockError = describeBlockError(error, text))
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(block = null, blockBusy = false, blockError = error.message ?: "Block lookup failed.")
                }
            }
        }
    }

    fun lookupTransaction(txid: String = _state.value.txQuery, blockHash: String? = null) {
        val text = txid.trim().lowercase()
        if (text.isEmpty()) {
            _state.update { it.copy(txError = "Enter a transaction id.", tx = null) }
            return
        }
        if (text.length != 64 || !RawTransaction.isHex(text)) {
            _state.update {
                it.copy(
                    tx = null,
                    txError = "A transaction id is 64 hexadecimal characters (${text.length} entered)."
                )
            }
            return
        }
        val client = NodeController.client()
        if (client == null) {
            _state.update { it.copy(txError = "Start the node before looking up transactions.", tx = null) }
            return
        }
        txJob?.cancel()
        txJob = scope.launch {
            _state.update { it.copy(txBusy = true, txError = null, txNote = null, txQuery = text) }
            try {
                val json = client.rawTransaction(text, blockHash)
                val tx = TxSummary.from(json)
                _state.update {
                    it.copy(
                        tx = tx,
                        txBusy = false,
                        txError = null,
                        txNote = if (tx.inMempool) "Unconfirmed: this transaction is in the mempool." else null
                    )
                }
            } catch (error: RpcException) {
                _state.update { it.copy(tx = null, txBusy = false, txError = describeTransactionError(error)) }
            } catch (error: Exception) {
                _state.update { it.copy(tx = null, txBusy = false, txError = error.message ?: "Lookup failed.") }
            }
        }
    }

    fun broadcast(input: String = _state.value.broadcastInput) {
        val parsed = RawTransaction.parse(input)
        if (parsed is RawTransaction.Parsed.Invalid) {
            _state.update { it.copy(broadcastError = parsed.reason, broadcastTxid = null) }
            return
        }
        val hex = (parsed as RawTransaction.Parsed.Valid).hex
        val client = NodeController.client()
        if (client == null) {
            _state.update { it.copy(broadcastError = "Start the node before broadcasting.", broadcastTxid = null) }
            return
        }
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            _state.update {
                it.copy(
                    broadcastBusy = true,
                    broadcastError = null,
                    broadcastTxid = null,
                    broadcastNote = null,
                    broadcastInput = hex
                )
            }
            try {
                val txid = client.sendRawTransaction(hex)
                _state.update {
                    it.copy(
                        broadcastBusy = false,
                        broadcastTxid = txid,
                        broadcastError = null,
                        broadcastNote = "Accepted by this node and relayed to its peers."
                    )
                }
                NodeController.refreshOnceNow()
            } catch (error: RpcException) {
                _state.update {
                    it.copy(
                        broadcastBusy = false,
                        broadcastTxid = null,
                        broadcastError = error.message ?: "The node rejected this transaction."
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        broadcastBusy = false,
                        broadcastTxid = null,
                        broadcastError = error.message ?: "Broadcast failed."
                    )
                }
            }
        }
    }

    private fun isPruned(error: RpcException): Boolean {
        val message = error.message.orEmpty()
        return (error.code == -1 && message.contains("pruned", true)) ||
            message.contains("Block not available", true)
    }

    private fun describeBlockError(error: RpcException, query: String): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("pruned", true) ->
                "That block's body was pruned and its header could not be read either. Try a recent block."
            message.contains("Block not found", true) ->
                "This node does not know block $query. It may not have reached that height yet."
            message.contains("out of range", true) ->
                "Height $query is above this node's chain tip. The newest block is " +
                    formatNumber(NodeController.state.value.blocks) + "."
            message.contains("not in active chain", true) ||
                message.contains("not on the main chain", true) ->
                "Block $query is not part of the active chain (a stale block of an old fork)."
            else -> message.ifBlank { "Block lookup failed." }
        }
    }

    private fun describeTransactionError(error: RpcException): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("No such mempool transaction", true) || message.contains("-txindex", true) ->
                "This node cannot find that transaction by id alone. A wallet-free node keeps no transaction " +
                    "index: confirmed transactions are only readable through the block that contains them, and " +
                    "only unconfirmed ones can be looked up directly. Open the block that included it and tap " +
                    "the transaction there."
            message.contains("No such mempool or blockchain transaction", true) ->
                "The node has no transaction with that id. Check the id, or look it up from its block."
            message.contains("hash must be of length 64", true) ->
                "A transaction id is 64 hexadecimal characters."
            else -> message.ifBlank { "Transaction lookup failed." }
        }
    }

    private fun formatNumber(value: Long): String =
        java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)
}
