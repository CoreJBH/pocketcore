package com.jbitmstr.pocketcore.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Watch-only descriptor scan shown in SETTINGS and EXPLORE. */
data class DescriptorState(
    val input: String = "",
    val normalized: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
    val progress: Double = 0.0,
    val error: String? = null,
    val result: ScanResult? = null,
    val scanHeight: Long = 0,
    val finishedAt: Long = 0
) {
    val balanceBtc: Double get() = result?.totalAmount ?: 0.0
    val utxos: List<ScannedUtxo> get() = result?.utxos ?: emptyList()
}

/**
 * Watch-only balance scanning without a wallet.
 *
 * `scantxoutset` walks the confirmed UTXO set for a public descriptor, so the
 * node never needs a wallet database, a key, or a key migration. The descriptor
 * itself is validated and, if needed, wrapped by [Descriptors] before it ever
 * reaches bitcoind.
 */
object DescriptorScanController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DescriptorState())
    val state: StateFlow<DescriptorState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var pollJob: Job? = null

    fun loadSaved(input: String?, normalized: String?) {
        if (_state.value.input.isNotBlank()) return
        _state.update { it.copy(input = input.orEmpty(), normalized = normalized) }
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text, error = null) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, error = null, note = null, progress = 0.0) }
    }

    fun scan(input: String = _state.value.input) {
        val parsed = Descriptors.parse(input)
        if (parsed is Descriptors.Parsed.Invalid) {
            _state.update { it.copy(error = parsed.reason, result = null) }
            return
        }
        val valid = parsed as Descriptors.Parsed.Valid
        val client = NodeController.client()
        if (client == null) {
            _state.update {
                it.copy(error = "Start the node before scanning. The scan runs on this phone's own UTXO set.")
            }
            return
        }
        scanJob?.cancel()
        pollJob?.cancel()
        scanJob = scope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = 0.0,
                    error = null,
                    note = valid.note,
                    input = input.trim(),
                    result = null
                )
            }
            try {
                val info = client.descriptorInfo(valid.descriptor)
                if (info.hasPrivateKeys) {
                    _state.update {
                        it.copy(busy = false, error = "That descriptor contains a private key. Only watch-only descriptors are allowed.")
                    }
                    return@launch
                }
                _state.update { it.copy(normalized = info.descriptor) }
                NodeController.settings.setDescriptor(input.trim(), info.descriptor)
                startProgressPolling(client)
                val json = client.scanTxOutSet(info.descriptor, info.isRange)
                val result = ScanResult.from(json)
                pollJob?.cancel()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 1.0,
                        result = result,
                        error = if (result.success) null else "The node did not complete the scan.",
                        note = buildList {
                            if (it.note != null) add(it.note!!)
                            if (!info.isSolvable) add("This descriptor is not solvable: balance only, no spending policy.")
                        }.joinToString(" ").ifBlank { it.note },
                        scanHeight = result.height,
                        finishedAt = System.currentTimeMillis()
                    )
                }
            } catch (error: RpcException) {
                pollJob?.cancel()
                _state.update { it.copy(busy = false, error = describeScanError(error)) }
            } catch (error: Exception) {
                pollJob?.cancel()
                _state.update { it.copy(busy = false, error = error.message ?: "Scan failed.") }
            }
        }
    }

    private fun startProgressPolling(client: RpcClient) {
        pollJob = scope.launch {
            while (isActive) {
                delay(1_500)
                val status = runCatching { client.scanStatus() }.getOrNull() ?: continue
                if (!status.has("progress")) continue
                val percent = status.optDouble("progress", 0.0)
                _state.update { it.copy(progress = (percent / 100.0).coerceIn(0.0, 0.999)) }
            }
        }
    }

    fun abort() {
        val client = NodeController.client() ?: return
        scope.launch {
            runCatching { client.scanAbort() }
            pollJob?.cancel()
            scanJob?.cancel()
            _state.update { it.copy(busy = false, progress = 0.0, error = "Scan cancelled.") }
        }
    }

    private fun describeScanError(error: RpcException): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("Scan already in progress", true) ->
                "A scan is already running. Wait for it to finish or cancel it."
            message.contains("Unable to start scan", true) ->
                "The node could not start the scan: $message"
            message.contains("is not a valid descriptor", true) ->
                "The node rejected this descriptor: $message"
            message.contains("checksum", true) ->
                "The descriptor checksum does not match its content. Remove the part after '#' and try again."
            message.contains("Range should not be specified", true) ->
                "This descriptor has no wildcard, so it needs no address range."
            else -> message.ifBlank { "Scan failed." }
        }
    }
}
