package com.corejbh.pocketcore.core

/**
 * Picks the RPC and P2P endpoints for the next bitcoind start.
 *
 * `InitHTTPServer()` treats a busy RPC port as fatal, so the endpoints are
 * settled before the daemon runs. Two things have to stay true while that
 * happens:
 *
 *  - the app's RPC credentials are only ever sent to a daemon of this app. The
 *    stop request is an HTTP Basic Auth call, so a foreign process holding the
 *    default port would receive the username and password; it is left alone
 *    instead and the node moves to a free port.
 *  - a daemon of this app that outlived a killed app process is stopped before
 *    the next one starts, because it still holds the data directory lock. The
 *    stop is graceful, and only a stop that fails or times out is followed by a
 *    kill: a hard kill of a live daemon can leave its LevelDB files half
 *    written.
 *
 * The probes are injected, so the whole decision is unit tested without a
 * socket.
 */
class PortResolver(
    private val isFree: (Int) -> Boolean,
    private val findFree: (Int, Set<Int>) -> Int,
    private val findFreeListenPair: (Int, Set<Int>) -> Int,
    private val awaitFree: (Int, Long) -> Boolean
) {

    data class Choice(
        val rpc: Int,
        val p2p: Int,
        val note: String?,
        /**
         * True when the default RPC port stayed in the hands of another app and
         * the node moved away from it. The credential pair is replaced for this
         * start, so nothing that may have seen an older one keeps a valid key.
         */
        val rotateCredentials: Boolean
    )

    /**
     * @param ownDaemonRpcPort RPC port of a daemon of this app that is already
     *        running, as recorded in the bitcoin.conf this app wrote for its
     *        last start; null when no daemon of this app is alive. This is the
     *        only port that may be sent a stop request.
     */
    suspend fun resolve(
        defaultRpc: Int,
        defaultP2p: Int,
        ownDaemonRpcPort: Int?,
        stopOwnDaemon: suspend (port: Int) -> Boolean,
        killStrays: () -> Unit
    ): Choice {
        val notes = ArrayList<String>(2)

        // 1. Clear a daemon of this app first (an orphan of a killed app
        //    process keeps the data directory lock). Nothing else is contacted.
        if (ownDaemonRpcPort != null) {
            val stopped = stopOwnDaemon(ownDaemonRpcPort)
            // A stop that was answered still has to release the endpoint; the
            // daemon writes its chain state out before it exits, which takes a
            // moment on a phone.
            val freed = stopped && awaitFree(ownDaemonRpcPort, 8_000)
            if (!freed) {
                // The graceful shutdown did not happen or did not finish. Only
                // now is the daemon killed: whatever is left under our name
                // still holds the datadir lock and the port.
                killStrays()
                // Wait for the daemon's own port to be released. When that port
                // was the default RPC port this also frees the endpoint the new
                // daemon needs; when it was a moved one, a foreign holder of the
                // default port is not waited on.
                awaitFree(ownDaemonRpcPort, 3_000)
            }
        }

        // 2. The P2P listen port, together with the loopback port Core binds one
        //    above it. A busy P2P port is not fatal (outbound still works) but a
        //    clean listen keeps the node reachable for inbound peers.
        var p2pPort = defaultP2p
        if (!isFree(p2pPort) || !isFree(p2pPort + 1)) {
            val chosen = findFreeListenPair(p2pPort, setOf(defaultRpc))
            if (chosen != p2pPort) {
                notes.add("P2P port $p2pPort is in use. Using $chosen for inbound peers.")
                p2pPort = chosen
            }
        }

        // 3. Move the RPC endpoint when something else on the phone keeps it.
        var rpcPort = defaultRpc
        var heldByAnotherApp = false
        val reservedByP2p = rpcPort == p2pPort || rpcPort == p2pPort + 1
        val rpcTaken = !isFree(rpcPort) || reservedByP2p
        if (rpcTaken) {
            val chosen = findFree(rpcPort, setOf(p2pPort, p2pPort + 1))
            if (chosen != rpcPort) {
                if (reservedByP2p) {
                    notes.add(
                        "RPC port $rpcPort collides with the P2P listener. PocketCore is using port $chosen."
                    )
                } else {
                    heldByAnotherApp = true
                    notes.add(
                        "RPC port $rpcPort is in use by another app. PocketCore is using port $chosen " +
                            "with a freshly generated RPC password."
                    )
                }
                rpcPort = chosen
            }
        }
        return Choice(
            rpc = rpcPort,
            p2p = p2pPort,
            note = notes.joinToString(" ").ifBlank { null },
            rotateCredentials = heldByAnotherApp && rpcPort != defaultRpc
        )
    }

    companion object {
        /** The resolver used on the device. */
        fun live(): PortResolver = PortResolver(
            isFree = PortProbe::isFree,
            findFree = { preferred, avoid -> PortProbe.findFree(preferred, avoid) },
            findFreeListenPair = { preferred, avoid -> PortProbe.findFreeListenPair(preferred, avoid) },
            awaitFree = PortProbe::awaitFree
        )
    }
}
