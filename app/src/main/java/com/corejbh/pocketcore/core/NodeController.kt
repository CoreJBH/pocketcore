package com.corejbh.pocketcore.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
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

/**
 * Whether the RPC credentials may be handed to a socket with owner [owner].
 *
 * Fail-closed, and the single place the rule is written down: only the listener
 * the daemon of this app itself holds receives them. [SocketOwner.FOREIGN] is
 * another process, [SocketOwner.NOT_LISTENING] cannot reach the daemon anyway,
 * and [SocketOwner.UNKNOWN] - a /proc entry that could not be read - leaves the
 * owner unproven, which is not a reason to hand out a password.
 */
internal fun credentialsAllowed(owner: SocketOwner): Boolean = owner == SocketOwner.OURS

/**
 * Whether an RPC request may be sent at all, before it is even built.
 *
 * Fail-closed like [credentialsAllowed], and for the same reason: the credential
 * pair exists only inside this process, so the only endpoint that may see it is
 * a listening socket this app proved is its own daemon's ([listenerVerified])
 * while that daemon is alive. [NodeStatus.RUNNING] is reached by the poll loop
 * only after that proof, so a state that is still starting, stopping, waiting or
 * failed has no client: work asked for in those states is refused rather than
 * queued against a port that could be somebody else's.
 */
internal fun clientAllowed(
    status: NodeStatus,
    listenerVerified: Boolean,
    alive: Boolean
): Boolean = status == NodeStatus.RUNNING && listenerVerified && alive

/**
 * Whether a closed gate may describe the node as WAITING.
 *
 * WAITING means "meant to be running, held back by the Wi-Fi / charger gate".
 * A node the user stopped (nothing alive, [shouldRun] false) stays STOPPED
 * when a settings edit or an environment change re-evaluates the policy - the
 * fifth review found a STOPPED node turning WAITING just because the settings
 * were edited while the phone was off Wi-Fi.
 */
internal fun gateMayWait(nodeAlive: Boolean, shouldRun: Boolean): Boolean =
    nodeAlive || shouldRun

/**
 * The START NOW bypass after a gate check.
 *
 * START NOW (`force`) starts the node although the Wi-Fi / charger gate is
 * closed; that is the point of it. The flag is not the setting, though: once
 * the real condition is met there is nothing left to bypass, and keeping the
 * flag would silently disable the gate for the rest of the run - the sixth
 * review found the node staying off Wi-Fi after a manual start because the
 * override was never dropped.
 */
internal fun forceOverrideAfterGate(forceOverride: Boolean, gateOpen: Boolean): Boolean =
    forceOverride && !gateOpen

/**
 * The state a transient RPC failure leaves behind.
 *
 * A reply that never came ([RpcCode.TRANSPORT]) or a "still loading" answer
 * ([RpcCode.IN_WARMUP]) says nothing about the daemon: a phone busy with the
 * chainstate misses one poll's timeout while the node is perfectly healthy.
 * Only the steps before RUNNING may be described as a warm-up, so a single slow
 * answer cannot walk a RUNNING node back to STARTING (the sixth review's
 * finding). A node before RUNNING keeps the existing behaviour: within the
 * startup grace the failure is the warm-up, after it the status stays as it is
 * and only the detail line changes.
 */
internal fun statusAfterTransientRpcFailure(status: NodeStatus, withinStartupGrace: Boolean): NodeStatus =
    when {
        status == NodeStatus.RUNNING -> NodeStatus.RUNNING
        withinStartupGrace -> NodeStatus.STARTING
        else -> status
    }

data class SnapshotModel(
    val path: String? = null,
    val label: String? = null,
    val sizeBytes: Long = 0L,
    val busy: Boolean = false,
    val result: String? = null,
    val error: String? = null
)

/**
 * State of an export (`dumptxoutset`) of this node's own UTXO set.
 *
 * The dump is written by the daemon into the app's cache, then copied to the
 * file the user picked; the temp file never outlives the export. The figures
 * Core reported are kept so the card can show the base height, the number of
 * coins and the UTXO set hash of what was written.
 */
data class SnapshotExportModel(
    val busy: Boolean = false,
    /** What the export is doing right now, e.g. "Dumping the UTXO set". */
    val stage: String? = null,
    /** Name of the document the user chose. */
    val fileName: String? = null,
    /** Size of the file that was written to that document. */
    val bytesWritten: Long? = null,
    val dump: TxOutSetDump? = null,
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
    val snapshotExport: SnapshotExportModel = SnapshotExportModel(),
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

    /** HTTP status Core answers when the credentials are not accepted. */
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403

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
    // Read from the poll loop, the UI-side actions and the service, so a write
    // from one coroutine is visible to the next reader without a lock.
    @Volatile private var rpc: RpcClient? = null
    private var startedAt = 0L
    private var lastPeerRefresh = 0L
    private var lastHeaderLogRead = 0L
    private var policyPaused = false
    private var forceOverride = false

    /** One automatic restart per start attempt, used when an RPC port is busy. */
    private var autoRetried = false
    @Volatile private var ready = false

    /**
     * True once the listening socket on the RPC port was seen to belong to the
     * daemon this app started (see [BitcoindNode.listenerOwner]). Reset for
     * every start.
     *
     * Read from the poll loop that sets it and from every coroutine that asks
     * [client] whether a request may go out, so a write here is visible to the
     * next reader without a lock.
     */
    @Volatile private var listenerVerified = false

    /**
     * The start and stop edges of the daemon's lifecycle.
     *
     * Every start ([beginStartLocked]) and every stop ([stopProcess]) takes the
     * next number. [refresh] remembers the number it began with and drops its
     * reply when the number has moved on: an RPC answer that arrives after the
     * node it describes was stopped (or started again) is a fact about a daemon
     * that no longer owns the state, and writing it back would turn a node the
     * user just stopped into a RUNNING one.
     */
    private val lifecycle = LifecycleGeneration()

    private fun isCurrent(generation: Long): Boolean = lifecycle.isCurrent(generation)

    /**
     * Applies [transform] only while [generation] is still the current one.
     *
     * Everything [refresh] learns through RPC belongs to the daemon that was
     * started in [generation]; a reply that arrives after a stop or a new start
     * is dropped instead of written over the newer state.
     */
    private fun updateIfCurrent(generation: Long, transform: (NodeUiState) -> NodeUiState) {
        if (!isCurrent(generation)) return
        _state.update(transform)
    }

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
        val blocked = gateBlocker(context)
        // START NOW is a bypass for the start it asks for, not a permanent one:
        // the flag is dropped as soon as the real condition is met, so a later
        // gate closure pauses the node the way the settings say
        // ([forceOverrideAfterGate]).
        forceOverride = forceOverrideAfterGate(forceOverride, gateOpen = blocked == null)
        return if (forceOverride) null else blocked
    }

    /** The Wi-Fi / charger gate as it stands, with no manual override applied. */
    private fun gateBlocker(context: Context): String? {
        val config = settings.state.value
        if (config.wifiOnly && !SystemState.isWifiConnected(context)) return "Waiting for Wi-Fi"
        if (config.chargingOnly && !SystemState.isCharging(context)) return "Waiting for charger"
        return null
    }

    /**
     * The JSON-RPC client for the port the daemon is actually listening on, or
     * null when no request may be sent right now.
     *
     * Null until this start's listening socket has been proved to be this app's
     * daemon ([listenerVerified]) and the node is RUNNING: the credentials must
     * never leave the app for an endpoint whose owner is still unproven, so a
     * caller that receives a client knows its request is allowed to go out. The
     * window a start opens - the free port was checked before the daemon bound
     * it - is exactly the window [clientAllowed] closes.
     */
    fun client(): RpcClient? = rpc?.takeIf {
        clientAllowed(_state.value.status, listenerVerified, node.isAlive())
    }

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
                // Only a node that is running or meant to run shows WAITING; a
                // stopped one stays stopped (see [gateMayWait]).
                if (gateMayWait(node.isAlive(), config.shouldRun) &&
                    _state.value.status != NodeStatus.WAITING
                ) {
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
     * (only such processes are visible in /proc here), whose endpoint is the
     * one recorded in the bitcoin.conf this app wrote, and whose own socket
     * table shows it holding the listening socket on that port. A port held by
     * anything else is left untouched and the node moves to a free one with a
     * new credential pair.
     */
    private suspend fun resolvePorts(config: NodeSettingsState): PortResolver.Choice {
        val ownPids = node.ownDaemonPids()
        val ownPid = ownPids.firstOrNull()
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
            stopOwnDaemon = { port ->
                // The daemon is only asked to stop with this start's own
                // credentials when its listening socket is proved to be the
                // daemon of this app ([listenerProvedOurs]): an orphan that is
                // still loading does not answer anyway, and a foreign listener
                // must never see the password.
                if (listenerProvedOurs(port, ownPid)) {
                    RpcClient(port, config.rpcUser, config.rpcPassword).stopQuick()
                } else {
                    // Android 10+ keeps that socket table unreadable, so the
                    // proof above cannot be had for an orphan. The orphan's own
                    // files carry what the stop needs instead ([OrphanStop]).
                    stopOrphanDaemon(port, config, ownPids)
                }
            },
            killStrays = { node.killStrayProcesses() }
        )
        return choice
    }

    /** How long a daemon that answered the stop RPC gets to finish and exit. */
    private const val ORPHAN_EXIT_WAIT_SECONDS = 12L

    /**
     * Ends a daemon of this app that this process did not start, with the stop
     * RPC rather than a kill, and waits for it to be gone.
     *
     * Android 10 and above keep `/proc/<pid>/net/tcp` unreadable, so
     * [listenerProvedOurs] cannot prove the listener and the previous build
     * always killed the orphan - which can leave the chainstate needing a
     * reindex. The three facts [OrphanStop] requires instead are all about this
     * app's own daemon and its own conf, and the credential is the pair that
     * daemon was started with.
     *
     * Returns true only once every pid in [pids] is gone: Core answers the stop
     * RPC and then writes its state out, so the caller's kill is held back until
     * the process itself has ended (or the bounded wait ran out).
     */
    private suspend fun stopOrphanDaemon(
        port: Int,
        config: NodeSettingsState,
        pids: List<Int>
    ): Boolean {
        val credentials = OrphanStop.credentials(
            cookie = NodePaths.readCookie(appContext, config.network),
            user = config.rpcUser,
            password = config.rpcPassword
        )
        if (!OrphanStop.maySend(pids, port, NodeConfig.readRpcPort(appContext), credentials)) {
            Log.w(TAG, "Not stopping the daemon on port $port gracefully: it is not this app's to contact.")
            return false
        }
        for (credential in credentials) {
            val outcome = runCatching {
                RpcClient(port, credential.user, credential.password).call("stop", timeoutMs = 5_000)
            }
            // A daemon that is already on its way out answers with "shutting
            // down" instead of a result. That is the same news as an answered
            // stop: the wait below is what decides whether it is gone.
            val answered = outcome.isSuccess ||
                outcome.exceptionOrNull()?.message?.contains("shutting down", true) == true
            if (!answered) continue
            return node.awaitPidExit(pids, ORPHAN_EXIT_WAIT_SECONDS)
        }
        return false
    }

    /**
     * True when the RPC credentials may be sent to [port] on behalf of [pid].
     *
     * Fail-closed: only a listening socket the daemon of this app itself holds
     * ([SocketOwner.OURS]) receives the credentials. Everything else is refused,
     * including [SocketOwner.UNKNOWN] - a port whose owner the /proc check and
     * the debug.log fallback ([ListenerFallback]) together could not establish.
     * An earlier build treated UNKNOWN as "probably ours"; that turned an
     * unreadable /proc into a channel for the password.
     *
     * A refusal is followed by killing this app's stray daemons, so the node is
     * not left retrying against a socket it cannot account for: the next start
     * finds the endpoint free and generates a fresh credential pair.
     */
    private fun maySendCredentials(port: Int, pid: Int?): Boolean {
        if (listenerProvedOurs(port, pid)) return true
        Log.w(TAG, "Not sending RPC credentials to port $port: its listener is not proven to be this node.")
        node.killStrayProcesses()
        return false
    }

    /**
     * True when the listening socket on [port] is proved to belong to the
     * daemon [pid] - by the /proc socket table where it can be read, by the
     * daemon's own debug.log where it cannot ([BitcoindNode.listenerOwner]).
     *
     * This is the proof the stop path above starts from; unlike
     * [maySendCredentials] a refusal here has no side effect, because the
     * Android 10+ caller still has the orphan's own files to try
     * ([stopOrphanDaemon]).
     */
    private fun listenerProvedOurs(port: Int, pid: Int?): Boolean =
        credentialsAllowed(node.listenerOwner(port, pid))

    private suspend fun beginStartLocked(config: NodeSettingsState) {
        // A new start: any reply still in flight describes the previous daemon.
        lifecycle.next()
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
        listenerVerified = false
        // The foreground service comes first: it keeps the app alive while the
        // daemon starts, and it must see STARTING so it does not start a second one.
        launchService()

        val ports = resolvePorts(config)
        // Hold both endpoints from here until the daemon is launched: the check
        // and the bind are separate steps, and while the configuration is being
        // written another app must not be able to claim the port that was just
        // found free. The holds are released right before node.start(), because
        // bitcoind has to bind the endpoints itself.
        val rpcHold = PortProbe.reserve(ports.rpc)
        val p2pHold = if (ports.p2p != ports.rpc) PortProbe.reserve(ports.p2p) else null
        try {
            // A fresh credential pair for every start, not only when a port had
            // to be left to another app: an RPC port is loopback only, but a
            // password that was ever written by an earlier build is not reused,
            // and a process that wins the race for the port between the check and
            // the bind has no password to answer with.
            settings.rotateCredentials()
            val activeConfig = settings.state.value
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
        } finally {
            rpcHold?.close()
            p2pHold?.close()
        }
        val error = node.start(settings.state.value.network)
        if (error != null) {
            fail(error)
            return
        }
        _state.update { it.copy(pid = node.pid()) }
        startPolling()
    }

    /** Suffix of a snapshot still being copied. */
    private const val PART_SUFFIX = ".part"

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
        // The lifecycle generation this pass is about. A start or stop that
        // happens while the requests below are in flight takes the next number,
        // and this pass then writes nothing: its reply describes a daemon that
        // has already been stopped or replaced.
        val generation = lifecycle.current()
        // A snapshot export holds Core while it walks the chainstate: polling it
        // would add RPC calls to the same process and flip the status line on a
        // timeout that only means "busy". The export card is what the screen
        // shows meanwhile, and the poll resumes as soon as it ends.
        if (_state.value.snapshotExport.busy) return
        val status = _state.value.status
        if (!node.isAlive()) {
            if ((status == NodeStatus.STARTING || status == NodeStatus.RUNNING) &&
                isCurrent(generation)
            ) {
                handleUnexpectedExit()
            }
            return
        }
        if (!listenerVerified) {
            // Before the first request of this start: the credentials are only
            // sent to a listener that belongs to the daemon just launched. The
            // free-port check ended before the daemon bound the endpoint, so
            // this is the moment the port could have changed hands.
            when (node.listenerOwner(client.port)) {
                SocketOwner.OURS -> listenerVerified = true
                SocketOwner.NOT_LISTENING -> {
                    // Core opens its RPC server late in startup (after the block
                    // index is loaded, which takes a while on a phone). Nothing
                    // to talk to yet, and nothing to complain about - until the
                    // startup grace has passed, when a daemon that is alive but
                    // never binds is a stuck one.
                    val warming = System.currentTimeMillis() - startedAt < STARTUP_GRACE_MS
                    updateIfCurrent(generation) {
                        it.copy(
                            status = NodeStatus.STARTING,
                            detail = if (warming) {
                                "Warming up the node"
                            } else {
                                "RPC is not answering (the port never opened)"
                            },
                            rpcReachable = false
                        )
                    }
                    return
                }
                SocketOwner.FOREIGN -> {
                    if (isCurrent(generation)) handleForeignPort(null)
                    return
                }
                SocketOwner.UNKNOWN -> {
                    // The owner of the port could not be established, even with
                    // the fallback (no PID, or no bind evidence in this start's
                    // log), so the credentials are withheld here too (the same
                    // fail-closed rule as [maySendCredentials]). A daemon that
                    // just started can take a moment to show its /proc entry, so
                    // the poll loop simply asks again; past the startup grace a
                    // port that stays unaccounted for is reported instead of used.
                    val warming = System.currentTimeMillis() - startedAt < STARTUP_GRACE_MS
                    updateIfCurrent(generation) {
                        it.copy(
                            status = NodeStatus.STARTING,
                            detail = if (warming) {
                                "Warming up the node"
                            } else {
                                "The RPC port could not be confirmed as this node's"
                            },
                            rpcReachable = false
                        )
                    }
                    return
                }
            }
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
            // The answers above are from this generation's daemon. If the user
            // pressed STOP meanwhile, they must not be written as RUNNING.
            updateIfCurrent(generation) {
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
            if (error.code == HTTP_UNAUTHORIZED || error.code == HTTP_FORBIDDEN) {
                // Something answered on the RPC port and refused this start's
                // credentials, which exist only in this process.
                if (isCurrent(generation)) handleForeignPort(error.code)
                return
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val withinGrace = elapsed < STARTUP_GRACE_MS
            // A transient failure must not walk a RUNNING node back to STARTING
            // ([statusAfterTransientRpcFailure]); the detail line still tells
            // the truth, and the next poll clears it when Core answers again.
            val running = status == NodeStatus.RUNNING
            when (error.code) {
                // Core is loading the block index and says so. That is the
                // startup the screen already describes, never an error, and it
                // is asked again on the next poll.
                RpcCode.IN_WARMUP -> updateIfCurrent(generation) {
                    it.copy(
                        status = statusAfterTransientRpcFailure(status, withinStartupGrace = true),
                        detail = if (running) "RPC is busy (${error.message})" else "Warming up the node",
                        rpcReachable = false
                    )
                }
                // No HTTP answer at all: within the startup grace that is still
                // the warm-up, after it the daemon is not answering.
                RpcCode.TRANSPORT -> updateIfCurrent(generation) {
                    it.copy(
                        status = statusAfterTransientRpcFailure(status, withinStartupGrace = withinGrace),
                        detail = when {
                            running -> "RPC did not answer (${error.message}). Retrying."
                            withinGrace -> "Warming up the node"
                            else -> "RPC is not answering (${error.message})"
                        },
                        rpcReachable = false
                    )
                }
                // Core answered, and the answer was an error (RPC_MISC_ERROR or
                // an HTTP status): the node is up, so the line is shown as it is
                // rather than being mistaken for a slow start.
                else -> updateIfCurrent(generation) {
                    it.copy(
                        detail = "bitcoind answered with an error: ${error.message}",
                        rpcReachable = false
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Polling failed: ${error.message}")
            updateIfCurrent(generation) { it.copy(rpcReachable = false) }
        }
    }

    /**
     * The RPC port is not this app's to use: it either answers with a refusal
     * for this start's credential pair ([code]) or its listening socket belongs
     * to some other process.
     *
     * The pair was generated for this start alone and never left the app, so the
     * daemon it launched is not the one holding the port: another process took
     * it after it was verified free. Authentication the app never sent is no
     * leak. The node moves to a verified free port once, and the retry is
     * limited by [autoRetried] so a port that keeps changing hands ends in a
     * clear error instead of a start loop.
     */
    private fun handleForeignPort(code: Int?) {
        pollJob?.cancel()
        pollJob = null
        scope.launch {
            mutex.withLock {
                val retry = !autoRetried && settings.state.value.shouldRun
                if (retry) {
                    autoRetried = true
                    node.killStrayProcesses()
                    _state.update {
                        it.copy(
                            status = NodeStatus.STARTING,
                            detail = "Another app is answering on the RPC port. Retrying on a free port.",
                            rpcReachable = false,
                            pid = null
                        )
                    }
                    beginStartLocked(settings.state.value)
                } else {
                    fail(
                        "Port ${_state.value.rpcPort} is " +
                            if (code != null) {
                                "answering, but not with this node's credentials (HTTP $code)."
                            } else {
                                "held by another process that is not this node."
                            } +
                            " Another app holds it. Stop the other app or restart the phone, " +
                            "then press START NODE."
                    )
                }
            }
        }
    }

    /**
     * The daemon is gone. If it died because its RPC port was taken, restart it
     * once on a verified free port; otherwise report the exact Core line.
     */
    private fun handleUnexpectedExit() {
        // The poll loop that found the daemon gone is retired at once, so no
        // second pass can enter here again while the restart is being decided.
        pollJob?.cancel()
        pollJob = null
        val detail = node.describeExit(settings.state.value.network)
        val portProblem = detail.contains("Unable to bind", true) ||
            detail.contains("Unable to start HTTP server", true) ||
            detail.contains("already running", true)
        scope.launch {
            mutex.withLock {
                if (node.isAlive()) return@withLock
                // Read and set under the same lock as every other start, so two
                // exits cannot both decide they are the one retry.
                val retry = !autoRetried && portProblem && settings.state.value.shouldRun
                if (retry) {
                    autoRetried = true
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
        // A stop: a reply that is already on its way must not resurrect RUNNING.
        lifecycle.next()
        pollJob?.cancel()
        pollJob = null
        if (!node.isAlive()) {
            node.destroy()
            rpc = null
            return
        }
        _state.update { it.copy(status = NodeStatus.STOPPING, detail = "Stopping bitcoind") }
        val client = rpc
        // A stop request is Basic Auth: it goes out only when the daemon is the
        // one listening on the port. If the endpoint changed hands, the process
        // is terminated without handing anyone the password.
        val maySend = client != null && maySendCredentials(client.port, node.pid())
        if (graceful && maySend) {
            runCatching { client.stop() }
            if (!node.awaitExit(25)) node.destroy()
        } else {
            // Either a hard stop was asked for, or the endpoint is not proven to
            // be this node's. Nothing is told the password: the process is ended
            // here, and [maySendCredentials] has already cleared any stray daemon
            // of this app so the next start finds a clean port.
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
                // The copy lands in a .part file next to the target and is renamed
                // only once it is complete and plausible: an interrupted or
                // rejected import leaves no half file behind for the daemon, and
                // the previous snapshot stays in place.
                val partial = File(target.absolutePath + PART_SUFFIX)
                try {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("The selected file cannot be read.")
                    stream.use { input ->
                        partial.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (partial.length() < 1024) {
                        throw IllegalStateException("That file is too small to be a UTXO snapshot.")
                    }
                    if (!partial.renameTo(target)) {
                        throw IllegalStateException("The snapshot could not be moved into place.")
                    }
                } catch (error: Throwable) {
                    runCatching { partial.delete() }
                    throw error
                }
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

    /**
     * Writes this node's current UTXO set with `dumptxoutset` and copies it into
     * the document the user picked.
     *
     * The daemon can only write to a path of its own (it runs as this app's UID),
     * so the dump lands in the cache directory first and is streamed into the
     * `Uri` afterwards; both the dump and Core's `<path>.incomplete` are removed
     * when the export ends, whatever the outcome. The dump itself is minutes of
     * chainstate walking on a phone, so the stage is reported while it runs and
     * the RPC poll is paused (see [refresh]) to keep Core free for the dump.
     */
    fun exportSnapshot(uri: android.net.Uri, displayName: String?) {
        scope.launch {
            val network = settings.state.value.network
            val name = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "pocketcore-${network.chainArg}.dat"
            // The same gate every other RPC caller goes through ([client]): a
            // dump is a long request to the daemon, and it may only be sent to a
            // port this app has proved is its own node. The gate is asked
            // before the card switches to "busy", so a stopped node gets the
            // refusal instead of a progress line that cannot advance.
            val client = client()
            if (client == null) {
                _state.update {
                    it.copy(
                        snapshotExport = SnapshotExportModel(
                            fileName = name,
                            error = "Start the node before exporting a snapshot."
                        )
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    snapshotExport = SnapshotExportModel(
                        busy = true,
                        stage = "Dumping the UTXO set",
                        fileName = name
                    )
                )
            }
            val temp = NodePaths.snapshotExportFile(appContext, network, System.currentTimeMillis())
            val partial = File(temp.absolutePath + INCOMPLETE_SUFFIX)
            val outcome = runCatching {
                // Core refuses a path that already exists, and a dump it started
                // earlier may have left one of these behind.
                temp.delete()
                partial.delete()
                val dump = TxOutSetDump.from(client.dumpTxOutSet(temp.absolutePath))
                if (!temp.exists() || temp.length() < 1024) {
                    throw IllegalStateException("Core reported a snapshot but left no file to copy.")
                }
                _state.update {
                    it.copy(snapshotExport = it.snapshotExport.copy(stage = "Saving the file"))
                }
                val stream = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("The chosen file could not be opened for writing.")
                stream.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                }
                dump to temp.length()
            }
            temp.delete()
            partial.delete()
            val saved = outcome.getOrNull()
            _state.update {
                it.copy(
                    snapshotExport = if (saved != null) {
                        SnapshotExportModel(
                            fileName = name,
                            bytesWritten = saved.second,
                            dump = saved.first
                        )
                    } else {
                        SnapshotExportModel(
                            fileName = name,
                            error = outcome.exceptionOrNull()?.message
                                ?: "The snapshot could not be exported."
                        )
                    }
                )
            }
            refreshOnceNow()
        }
    }

    /** Kept next to [PART_SUFFIX]: Core's own suffix for a dump in progress. */
    private const val INCOMPLETE_SUFFIX = ".incomplete"

    fun clearExportResult() {
        _state.update { it.copy(snapshotExport = SnapshotExportModel()) }
    }

    fun loadSnapshot() {
        scope.launch {
            // Through the gate as well: loading a snapshot writes the chainstate
            // of the daemon on the other end, and that must be this app's node.
            val client = client()
            val path = settings.state.value.snapshotPath
            if (client == null) {
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

    fun wipeNodeData(
        onDone: (String) -> Unit = {},
        selectedNetworkOnly: Boolean = false
    ) {
        scope.launch {
            mutex.withLock {
                settings.setShouldRun(false)
                stopProcess(graceful = true)
                NodeService.stopService(appContext)
                node.killStrayProcesses()
                val dir = NodePaths.dataDir(appContext)
                val network = settings.state.value.network
                val deleted = if (selectedNetworkOnly) {
                    // A Full (prune=0) rebuild only has to wipe the chain whose
                    // database is pruned: mainnet's files at the data directory
                    // root, or the network's own subdirectory - never the other
                    // networks' chains (see [NodePaths.networkChainEntries]).
                    NodePaths.networkChainEntries(dir, network).all { it.deleteRecursively() }
                } else {
                    dir.deleteRecursively().also { dir.mkdirs() }
                }
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
                        message = when {
                            deleted && selectedNetworkOnly ->
                                "${network.label} chain data deleted. The next start downloads this chain again from genesis."
                            deleted -> "Node data deleted."
                            else -> "Node data could not be fully deleted."
                        }
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
     * again from genesis. Only the selected network's chain is deleted: the
     * fifth review found the wipe taking the other networks' chains with it,
     * whose data the pruned flag does not concern at all.
     */
    fun rebuildChainAsFull() {
        settings.setPruneMb(0)
        wipeNodeData(selectedNetworkOnly = true)
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
