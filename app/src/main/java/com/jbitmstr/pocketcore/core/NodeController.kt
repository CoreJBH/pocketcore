package com.jbitmstr.pocketcore.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

enum class NodeStatus { STOPPED, WAITING, STARTING, RUNNING, STOPPING, ERROR }

data class SnapshotModel(
    val path: String? = null,
    val label: String? = null,
    val sizeBytes: Long = 0L,
    val busy: Boolean = false,
    val result: String? = null,
    val error: String? = null
)

data class NodeUiState(
    val status: NodeStatus = NodeStatus.STOPPED,
    val detail: String? = null,
    val config: NodeSettingsState = NodeSettingsState(),
    val blockchain: BlockchainInfo? = null,
    /** Header progress from debug.log while RPC still reports headers = 0. */
    val headerSync: HeaderSync? = null,
    val network: NetworkInfo? = null,
    val mempool: MempoolInfo? = null,
    val peers: List<PeerInfo> = emptyList(),
    val chainStates: List<ChainStateInfo> = emptyList(),
    val uptimeSeconds: Long? = null,
    val binaryVersion: String = "",
    val rpcReachable: Boolean = false,
    val pid: Int? = null,
    val lastUpdated: Long = 0L,
    val snapshot: SnapshotModel = SnapshotModel(),
    val message: String? = null,
    val networkLabel: String = "",
    /** Port actually handed to bitcoind; 0 means "the network default". */
    val effectiveRpcPort: Int = 0,
    val effectiveP2pPort: Int = 0,
    /** Set when a port was moved because something else held it. */
    val portNote: String? = null
) {
    val blocks: Long get() = blockchain?.blocks ?: 0L
    val headers: Long get() = blockchain?.headers ?: 0L
    val progress: Double get() = blockchain?.verificationProgress ?: 0.0
    val isRunning: Boolean get() = status == NodeStatus.RUNNING
    val isSyncing: Boolean get() = blockchain?.initialBlockDownload == true || status == NodeStatus.STARTING
    val snapshotActive: Boolean get() = chainStates.any { it.snapshot }
    val messageId: Long get() = lastUpdated
    val rpcPort: Int get() = if (effectiveRpcPort > 0) effectiveRpcPort else config.network.rpcPort
    val p2pPort: Int get() = if (effectiveP2pPort > 0) effectiveP2pPort else config.network.p2pPort
    val customRpcPort: Boolean get() = rpcPort != config.network.rpcPort
}

/**
 * Single owner of the node: process lifecycle, RPC polling, port selection,
 * snapshot loading and the Wi-Fi / charging policy. The UI and the foreground
 * service both read [state] and call the functions here.
 */
object NodeController {

    private const val TAG = "PocketCore"
    private const val STARTUP_GRACE_MS = 120_000L

    /** debug.log is read for header progress at most this often. */
    private const val HEADER_LOG_INTERVAL_MS = 4_000L

    /** Tail of debug.log searched for the newest header progress line. */
    private const val HEADER_LOG_LINES = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val portResolver = PortResolver.live()

    private lateinit var appContext: Context
    private lateinit var node: BitcoindNode
    lateinit var settings: NodeSettings
        private set

    private val _state = MutableStateFlow(NodeUiState())
    val state: StateFlow<NodeUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var rpc: RpcClient? = null
    private var startedAt = 0L
    private var lastPeerRefresh = 0L
    private var lastHeaderLogRead = 0L
    private var policyPaused = false
    private var forceOverride = false

    /** One automatic restart per start attempt, used when an RPC port is busy. */
    private var autoRetried = false
    @Volatile private var ready = false

    fun init(context: Context) {
        synchronized(this) {
            if (ready) return
            appContext = context.applicationContext
            settings = NodeSettings(appContext)
            node = BitcoindNode(appContext)
            _state.value = NodeUiState(
                config = settings.state.value,
                snapshot = SnapshotModel(
                    path = settings.state.value.snapshotPath,
                    label = settings.state.value.snapshotLabel,
                    sizeBytes = settings.state.value.snapshotPath?.let { File(it).length() } ?: 0L
                ),
                networkLabel = SystemState.networkLabel(appContext),
                effectiveRpcPort = settings.state.value.network.rpcPort,
                effectiveP2pPort = settings.state.value.network.p2pPort
            )
            ready = true
        }
        scope.launch {
            val version = node.version()
            if (version.isNotEmpty()) _state.update { it.copy(binaryVersion = version) }
        }
        scope.launch {
            // The peer country table is a 130 KB asset: decompress it before the
            // first peer list is drawn, never on the UI thread.
            GeoIp.ensureLoaded(appContext)
        }
        scope.launch {
            // Resume a node that was running when the app process was recreated.
            if (settings.state.value.shouldRun && !node.isAlive() &&
                _state.value.status == NodeStatus.STOPPED
            ) {
                start()
            }
        }
    }

    fun gateReason(context: Context = appContext): String? {
        val config = settings.state.value
        if (forceOverride) return null
        if (config.wifiOnly && !SystemState.isWifiConnected(context)) return "Waiting for Wi-Fi"
        if (config.chargingOnly && !SystemState.isCharging(context)) return "Waiting for charger"
        return null
    }

    /** The JSON-RPC client for the port the daemon is actually listening on. */
    fun client(): RpcClient? = rpc?.takeIf { node.isAlive() }

    fun start(force: Boolean = false) {
        scope.launch {
            mutex.withLock {
                if (node.isAlive()) return@withLock
                if (force) forceOverride = true
                settings.setShouldRun(true)
                autoRetried = false
                val config = settings.state.value
                val gate = gateReason()
                if (gate != null && !force) {
                    policyPaused = true
                    _state.update {
                        it.copy(
                            status = NodeStatus.WAITING,
                            detail = gate,
                            config = config,
                            message = null
                        )
                    }
                    launchService()
                    return@withLock
                }
                policyPaused = false
                beginStartLocked(config)
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                settings.setShouldRun(false)
                policyPaused = false
                forceOverride = false
                // Stop the daemon first, then retire the foreground service, so the
                // graceful shutdown is not interrupted by the activity going away.
                stopProcess(graceful = true)
                NodeService.stopService(appContext)
                _state.update {
                    it.copy(
                        status = NodeStatus.STOPPED,
                        detail = null,
                        blockchain = null,
                        headerSync = null,
                        network = null,
                        mempool = null,
                        peers = emptyList(),
                        chainStates = emptyList(),
                        uptimeSeconds = null,
                        rpcReachable = false,
                        pid = null,
                        message = null
                    )
                }
            }
        }
    }

    fun restart() {
        scope.launch {
            mutex.withLock {
                settings.setShouldRun(true)
                val config = settings.state.value
                if (node.isAlive()) stopProcess(graceful = true)
                autoRetried = false
                beginStartLocked(config)
            }
        }
    }

    fun applySettingsAndMaybeRestart(restartIfRunning: Boolean = true) {
        scope.launch {
            mutex.withLock {
                val config = settings.state.value
                val networkChanged = _state.value.config.network != config.network
                _state.update {
                    it.copy(
                        config = config,
                        effectiveRpcPort = if (networkChanged || it.effectiveRpcPort <= 0) {
                            config.network.rpcPort
                        } else {
                            it.effectiveRpcPort
                        },
                        effectiveP2pPort = if (networkChanged || it.effectiveP2pPort <= 0) {
                            config.network.p2pPort
                        } else {
                            it.effectiveP2pPort
                        },
                        portNote = if (networkChanged) null else it.portNote
                    )
                }
                if (node.isAlive()) {
                    if (restartIfRunning) {
                        stopProcess(graceful = true)
                        autoRetried = false
                        beginStartLocked(config)
                    }
                } else if (_state.value.status == NodeStatus.WAITING) {
                    val gate = gateReason()
                    if (gate == null && config.shouldRun) beginStartLocked(config)
                }
            }
            evaluatePolicy()
        }
    }

    fun onEnvironmentChanged() {
        _state.update { it.copy(networkLabel = SystemState.networkLabel(appContext)) }
        scope.launch { evaluatePolicy() }
    }

    /**
     * Wi-Fi only / charging only: a gate closes, the daemon is stopped through
     * the stop RPC; the gate opens again, the daemon is started again.
     *
     * The whole evaluation runs under [mutex], together with the state change it
     * decides on: a start or stop racing with the gate would otherwise leave
     * [policyPaused] and the daemon disagreeing about who paused what.
     */
    private suspend fun evaluatePolicy() {
        mutex.withLock {
            val config = settings.state.value
            val gate = gateReason()
            if (gate != null) {
                if (node.isAlive()) {
                    policyPaused = true
                    stopProcess(graceful = true)
                }
                if (_state.value.status != NodeStatus.WAITING) {
                    _state.update {
                        it.copy(status = NodeStatus.WAITING, detail = gate, config = config)
                    }
                }
            } else if (policyPaused && config.shouldRun && !node.isAlive()) {
                policyPaused = false
                beginStartLocked(config)
            }
        }
    }

    /**
     * Keeps the RPC / P2P ports of a new start usable.
     *
     * A phone can keep an orphan bitcoind alive after the app process died, and
     * another node app may hold the default port. Because `InitHTTPServer()` is
     * fatal on a busy RPC port, the app resolves this before the daemon starts.
     *
     * A stop request carries the RPC credentials in Basic Auth, so it is only
     * ever sent to a daemon of this app: one whose process runs under our UID
     * (only such processes are visible in /proc here) and whose endpoint is the
     * one recorded in the bitcoin.conf this app wrote. A port held by anything
     * else is left untouched and the node moves to a free one with a new
     * credential pair.
     */
    private suspend fun resolvePorts(config: NodeSettingsState): PortResolver.Choice {
        val ownPids = node.ownDaemonPids()
        val ownPort = if (ownPids.isEmpty()) null else NodeConfig.readRpcPort(appContext)
        if (ownPids.isNotEmpty() && ownPort == null) {
            // A daemon of ours runs but its endpoint is not recorded (no conf
            // yet): nothing may receive our credentials, so it is killed and
            // the free port is chosen below.
            node.killStrayProcesses()
        }
        val choice = portResolver.resolve(
            defaultRpc = config.network.rpcPort,
            defaultP2p = config.network.p2pPort,
            ownDaemonRpcPort = ownPort,
            stopOwnDaemon = { port -> RpcClient(port, config.rpcUser, config.rpcPassword).stopQuick() },
            killStrays = { node.killStrayProcesses() }
        )
        return choice
    }

    private suspend fun beginStartLocked(config: NodeSettingsState) {
        pollJob?.cancel()
        pollJob = null
        _state.update {
            it.copy(
                status = NodeStatus.STARTING,
                detail = "Preparing bitcoind",
                config = config,
                blockchain = null,
                headerSync = null,
                network = null,
                mempool = null,
                peers = emptyList(),
                chainStates = emptyList(),
                uptimeSeconds = null,
                rpcReachable = false,
                pid = null,
                message = null
            )
        }
        lastHeaderLogRead = 0L
        // The foreground service comes first: it keeps the app alive while the
        // daemon starts, and it must see STARTING so it does not start a second one.
        launchService()

        val ports = resolvePorts(config)
        // A port left to another app means the old pair may have been seen by
        // something that is not ours: replace it before this start.
        val activeConfig = if (ports.rotateCredentials) {
            settings.rotateCredentials()
            settings.state.value
        } else {
            config
        }
        NodeConfig.write(appContext, activeConfig, ports.rpc, ports.p2p)
        rpc = RpcClient(ports.rpc, activeConfig.rpcUser, activeConfig.rpcPassword)
        startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                status = NodeStatus.STARTING,
                detail = ports.note ?: "Starting bitcoind",
                config = activeConfig,
                effectiveRpcPort = ports.rpc,
                effectiveP2pPort = ports.p2p,
                portNote = ports.note
            )
        }
        val error = node.start()
        if (error != null) {
            fail(error)
            return
        }
        _state.update { it.copy(pid = node.pid()) }
        startPolling()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        pollJob?.cancel()
        pollJob = null
        settings.setShouldRun(false)
        _state.update {
            it.copy(
                status = NodeStatus.ERROR,
                detail = message,
                rpcReachable = false,
                pid = null,
                message = message
            )
        }
        NodeService.stopService(appContext)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(if (_state.value.status == NodeStatus.STARTING) 900 else 2200)
            }
        }
    }

    private suspend fun refresh() {
        val client = rpc ?: return
        val status = _state.value.status
        if (!node.isAlive()) {
            if (status == NodeStatus.STARTING || status == NodeStatus.RUNNING) {
                handleUnexpectedExit()
            }
            return
        }
        try {
            val chain = client.blockchainInfo()
            val network = client.networkInfo()
            val mempool = client.mempoolInfo()
            val uptime = client.uptime()
            val now = System.currentTimeMillis()
            // A header chain that has not been committed yet reports headers = 0
            // and 0.00% over RPC, so the real position is read from debug.log
            // (throttled: the log line is written several times per second).
            val headerSync = when {
                chain.headers > 0L -> null
                now - lastHeaderLogRead >= HEADER_LOG_INTERVAL_MS -> {
                    lastHeaderLogRead = now
                    val log = NodePaths.readTail(
                        NodePaths.debugLog(appContext, settings.state.value.network),
                        HEADER_LOG_LINES
                    )
                    HeaderSync.latest(log)
                }
                else -> _state.value.headerSync
            }
            val peers = if (now - lastPeerRefresh > 4_000) {
                lastPeerRefresh = now
                client.peers()
            } else {
                _state.value.peers
            }
            val chainStates = runCatching { client.chainStates() }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    status = NodeStatus.RUNNING,
                    detail = if (chain.initialBlockDownload) "Initial block download" else null,
                    blockchain = chain,
                    headerSync = headerSync,
                    network = network,
                    mempool = mempool,
                    uptimeSeconds = uptime,
                    peers = peers,
                    chainStates = chainStates,
                    rpcReachable = true,
                    lastUpdated = now,
                    pid = node.pid(),
                    networkLabel = SystemState.networkLabel(appContext)
                )
            }
        } catch (error: RpcException) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (error.code == -1 && elapsed < STARTUP_GRACE_MS) {
                _state.update {
                    it.copy(
                        status = NodeStatus.STARTING,
                        detail = "Warming up the node",
                        rpcReachable = false
                    )
                }
            } else if (error.code == -1) {
                _state.update {
                    it.copy(
                        detail = "RPC is not answering (${error.message})",
                        rpcReachable = false
                    )
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Polling failed: ${error.message}")
            _state.update { it.copy(rpcReachable = false) }
        }
    }

    /**
     * The daemon is gone. If it died because its RPC port was taken, restart it
     * once on a verified free port; otherwise report the exact Core line.
     */
    private fun handleUnexpectedExit() {
        val config = settings.state.value
        val detail = node.describeExit(config.network)
        val portProblem = detail.contains("Unable to bind", true) ||
            detail.contains("Unable to start HTTP server", true) ||
            detail.contains("already running", true)
        val retry = !autoRetried && portProblem && settings.state.value.shouldRun
        if (retry) autoRetried = true
        scope.launch {
            mutex.withLock {
                if (node.isAlive()) return@withLock
                if (retry) {
                    node.killStrayProcesses()
                    _state.update {
                        it.copy(
                            status = NodeStatus.STARTING,
                            detail = "RPC port was busy. Retrying on a free port.",
                            rpcReachable = false,
                            pid = null
                        )
                    }
                    beginStartLocked(settings.state.value)
                } else {
                    fail(friendlyFailure(detail, _state.value.rpcPort))
                }
            }
        }
    }

    private fun friendlyFailure(coreMessage: String, port: Int): String {
        val message = node.cleanLogLine(coreMessage)
        return when {
            message.contains("Unable to bind", true) || message.contains("already running", true) ->
                "Port $port is held by another process on this phone, and moving to a free port did not " +
                    "work either. Stop the other node app, or restart the phone, then press START NODE. " +
                    "Core said: $message"
            message.contains("Unable to start HTTP server", true) ->
                "bitcoind could not open its RPC server. Core said: $message"
            message.contains("Cannot obtain a lock", true) ->
                "Another bitcoind is using the same data directory. Restart the app so it can be cleared. " +
                    "Core said: $message"
            else -> message
        }
    }

    private suspend fun stopProcess(graceful: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (!node.isAlive()) {
            node.destroy()
            rpc = null
            return
        }
        _state.update { it.copy(status = NodeStatus.STOPPING, detail = "Stopping bitcoind") }
        if (graceful) {
            val client = rpc
            if (client != null) {
                runCatching { client.stop() }
            }
            if (!node.awaitExit(25)) node.destroy()
        } else {
            node.destroy()
        }
        rpc = null
    }

    // --- Peer actions ------------------------------------------------------

    fun disconnectPeer(address: String) {
        scope.launch {
            val client = client() ?: return@launch
            runCatching { client.disconnectNode(address) }
                .onFailure { error ->
                    _state.update { it.copy(message = "Disconnect failed: ${error.message}") }
                }
                .onSuccess {
                    _state.update { it.copy(message = "Disconnected $address") }
                    refreshOnceNow()
                }
        }
    }

    fun banPeer(address: String, host: String, hours: Long = 24) {
        scope.launch {
            val client = client() ?: return@launch
            // Core wants a prefix for IPv6 subnets (2001:db8::1/128).
            val subnet = if (host.contains(':') && !host.contains('/')) "$host/128" else host
            runCatching { client.setBan(subnet, "add", hours * 3_600) }
                .onFailure { error ->
                    _state.update { it.copy(message = "Ban failed: ${error.message}") }
                }
                .onSuccess {
                    runCatching { client.disconnectNode(address) }
                    _state.update {
                        it.copy(message = "Banned $host for $hours hours")
                    }
                    refreshOnceNow()
                }
        }
    }

    // --- AssumeUTXO snapshots ---------------------------------------------

    fun importSnapshot(uri: android.net.Uri, displayName: String?) {
        scope.launch {
            _state.update { it.copy(snapshot = it.snapshot.copy(busy = true, error = null, result = null)) }
            val result = runCatching {
                val label = displayName?.substringAfterLast('/') ?: "snapshot.dat"
                val target = NodeConfig.snapshotTarget(appContext, label)
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file cannot be read.")
                stream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() < 1024) throw IllegalStateException("That file is too small to be a UTXO snapshot.")
                settings.setSnapshot(target.absolutePath, label)
                target
            }
            val target = result.getOrNull()
            _state.update {
                it.copy(
                    config = settings.state.value,
                    snapshot = if (target != null) {
                        SnapshotModel(
                            path = target.absolutePath,
                            label = settings.state.value.snapshotLabel,
                            sizeBytes = target.length(),
                            result = "Snapshot file ready. Load it while the node is running."
                        )
                    } else {
                        it.snapshot.copy(busy = false, error = result.exceptionOrNull()?.message ?: "Import failed")
                    }
                )
            }
        }
    }

    fun clearSnapshot() {
        scope.launch {
            val path = settings.state.value.snapshotPath
            if (path != null) runCatching { File(path).delete() }
            settings.setSnapshot(null, null)
            _state.update {
                it.copy(
                    config = settings.state.value,
                    snapshot = SnapshotModel()
                )
            }
        }
    }

    fun loadSnapshot() {
        scope.launch {
            val client = rpc
            val path = settings.state.value.snapshotPath
            if (client == null || !node.isAlive()) {
                _state.update { it.copy(snapshot = it.snapshot.copy(error = "Start the node before loading a snapshot.")) }
                return@launch
            }
            if (path == null || !File(path).exists()) {
                _state.update { it.copy(snapshot = it.snapshot.copy(error = "Choose a snapshot file first.")) }
                return@launch
            }
            _state.update { it.copy(snapshot = it.snapshot.copy(busy = true, error = null, result = null)) }
            val outcome = runCatching { client.loadTxOutSet(path) }
            val json: JSONObject? = outcome.getOrNull()
            _state.update {
                it.copy(
                    snapshot = if (json != null) {
                        it.snapshot.copy(
                            busy = false,
                            result = describeLoadResult(json),
                            error = null
                        )
                    } else {
                        it.snapshot.copy(
                            busy = false,
                            error = outcome.exceptionOrNull()?.message ?: "loadtxoutset failed"
                        )
                    }
                )
            }
            refreshOnceNow()
        }
    }

    private fun describeLoadResult(json: JSONObject): String {
        val height = json.optLong("base_height", json.optLong("height"))
        val coins = json.optLong("coins_loaded")
        val hash = json.optString("tip_hash", json.optString("hash", ""))
        val short = if (hash.length >= 16) hash.take(16) + "..." else hash
        return buildString {
            append("Snapshot activated")
            if (height > 0) append(" at block ${formatCount(height)}")
            if (coins > 0) append(" with ${formatCount(coins)} coins")
            if (short.isNotEmpty()) append(" ($short)")
            append(". Validating the full chain in the background.")
        }
    }

    private fun formatCount(value: Long): String =
        java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)

    fun refreshOnceNow() {
        scope.launch { refresh() }
    }

    fun wipeNodeData(onDone: (String) -> Unit = {}) {
        scope.launch {
            mutex.withLock {
                settings.setShouldRun(false)
                stopProcess(graceful = true)
                NodeService.stopService(appContext)
                node.killStrayProcesses()
                val dir = NodePaths.dataDir(appContext)
                val deleted = dir.deleteRecursively()
                dir.mkdirs()
                _state.update {
                    it.copy(
                        status = NodeStatus.STOPPED,
                        detail = null,
                        config = settings.state.value,
                        blockchain = null,
                        headerSync = null,
                        network = null,
                        mempool = null,
                        peers = emptyList(),
                        chainStates = emptyList(),
                        uptimeSeconds = null,
                        rpcReachable = false,
                        pid = null,
                        portNote = null,
                        effectiveRpcPort = settings.state.value.network.rpcPort,
                        effectiveP2pPort = settings.state.value.network.p2pPort,
                        message = if (deleted) "Node data deleted." else "Node data could not be fully deleted."
                    )
                }
                onDone(_state.value.message ?: "")
            }
        }
    }

    /**
     * Switches to `prune=0` on a phone whose blocks were already pruned.
     *
     * Core keeps a flag for "a block file has been deleted" and refuses to open
     * such a database as an unpruned node ("You need to rebuild the database
     * using -reindex to go back to unpruned mode"). The chain data is therefore
     * deleted - SETTINGS asks first - and the next start downloads the chain
     * again from genesis.
     */
    fun rebuildChainAsFull() {
        settings.setPruneMb(0)
        wipeNodeData()
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Last lines of the running network's debug.log, for the settings dialog. */
    fun debugLog(maxLines: Int = 400): String {
        val network = settings.state.value.network
        val text = NodePaths.readTail(NodePaths.debugLog(appContext, network), maxLines)
        return text.ifBlank { "No log yet. Start the node to create one." }
    }

    fun debugLogPath(): String = NodePaths.debugLog(appContext, settings.state.value.network).absolutePath

    /** Kept for callers that only want the tail. */
    fun tailLog(): String = debugLog()

    private fun launchService() {
        val intent = Intent(appContext, NodeService::class.java)
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure { error ->
            Log.w(TAG, "Foreground service could not be started: ${error.message}")
        }
    }
}
