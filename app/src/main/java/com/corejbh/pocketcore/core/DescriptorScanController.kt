package com.corejbh.pocketcore.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * The state ABORT leaves behind.
 *
 * The card must come out of "scanning" whether or not the node can be asked:
 * a scan that was running when the node was stopped (or that the daemon had
 * already finished) otherwise kept its progress line and its busy flag with
 * nothing behind them, and ABORT did nothing at all - the sixth review's
 * finding. Only a scan that was actually running reports the cancellation; an
 * idle card keeps what it was showing.
 */
internal fun abortedScanState(current: DescriptorState): DescriptorState = current.copy(
    busy = false,
    progress = 0.0,
    error = if (current.busy) "Scan cancelled." else current.error
)

/**
 * The steps of ABORT, in the order they have to run.
 *
 * The scan job is cancelled *before* the daemon is asked to abort: scanAbort()
 * makes the running scantxoutset return at once - with an error, not a result -
 * and a scan job still alive would write that error over the state this abort
 * publishes. The cancelled state is published before the request goes out, so
 * the card stops reporting a scan even while the daemon takes its time
 * answering, or does not answer at all (versionCode 20).
 */
internal suspend fun abortInOrder(
    cancelScan: () -> Unit,
    publishCancelled: () -> Unit,
    askDaemon: suspend () -> Unit
) {
    cancelScan()
    publishCancelled()
    askDaemon()
}

/**
 * The answer of a blocking daemon call, handed back only while the job that made
 * it is still wanted.
 *
 * `scantxoutset` is one long HTTP request: cancelling the scan marks the job
 * cancelled but does not interrupt the call, so a scan the user cancelled (or
 * replaced with a new one) still gets its reply. The check after the call turns
 * that reply into the cancellation the job already carries, instead of letting
 * it be written over the state ABORT published (versionCode 20).
 */
internal suspend fun <T> answerIfStillWanted(block: suspend () -> T): T {
    val answer = block()
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    return answer
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
        scanJob?.cancel()
        pollJob?.cancel()
        scanJob = scope.launch {
            // `scantxoutset` walks the chainstate for as long as it takes, and it is one
            // of the requests the sensitive gate covers: the node has to be running, its
            // listener proved again now, the user still asking for it to run and no start
            // or stop since this decision (versionCode 21). The gate reads /proc and the
            // daemon's log, so it runs here on the IO dispatcher and not on the frame
            // that runs the SCAN button.
            val open = NodeController.openSensitiveRpc()
            val call = open.call
            if (call == null) {
                _state.update {
                    it.copy(busy = false, error = open.refusal ?: "The node is not ready for a scan.")
                }
                return@launch
            }
            val client = call.client
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
                // The reply is published only if this job is still wanted: a
                // cancel that arrived while the daemon was walking the UTXO set
                // is not interruptible, and the answer would otherwise land on
                // top of the state ABORT already published (versionCode 20).
                val json = answerIfStillWanted { client.scanTxOutSet(info.descriptor, info.isRange) }
                val problem = NodeController.sensitiveReplyProblem(call)
                if (problem != null) {
                    pollJob?.cancel()
                    _state.update {
                        it.copy(
                            busy = false,
                            progress = 0.0,
                            error = "The scan finished, but the node changed while it ran: $problem " +
                                "Start the node and scan again."
                        )
                    }
                    return@launch
                }
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
            } catch (error: CancellationException) {
                // The scan was cancelled (a new scan, or ABORT). That is not a
                // failure to report, and swallowing it would break the
                // cancellation contract of the coroutine that runs it.
                throw error
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
        val client = NodeController.client()
        scope.launch {
            abortInOrder(
                cancelScan = {
                    pollJob?.cancel()
                    pollJob = null
                    scanJob?.cancel()
                    scanJob = null
                },
                publishCancelled = { _state.update { abortedScanState(it) } },
                // The daemon is asked only when a client exists; the state is
                // reset either way (see [abortedScanState]).
                askDaemon = { if (client != null) runCatching { client.scanAbort() } }
            )
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
