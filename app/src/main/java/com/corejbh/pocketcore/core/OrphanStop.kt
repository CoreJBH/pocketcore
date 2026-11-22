package com.corejbh.pocketcore.core

/** A loopback RPC credential pair: the user Core checks its `rpcauth` hash against. */
data class RpcCredential(val user: String, val password: String)

/**
 * Ending a daemon of this app that outlived the app process.
 *
 * A node that keeps running after the app process was killed still holds the
 * data directory lock and the RPC port, so the next start has to end it first.
 * Until versionCode 15 that was always a SIGKILL: on Android 10+ the /proc
 * socket tables that used to prove the listener is the daemon cannot be read
 * (SELinux), the credentials were withheld from a port whose owner was
 * unproven, and the kill was the only route left. A kill in the middle of a
 * LevelDB write can leave the chainstate needing a reindex, so the stop RPC -
 * which ends Core after it has written its state out - is tried first.
 *
 * The request is only built when three facts hold, and every one of them is
 * about this app's own daemon rather than about the port:
 *
 *  - [ownDaemonPids] is not empty: a process of this app's UID is running the
 *    bundled bitcoind (readable on Android 10+; /proc/<pid>/status and cmdline
 *    are the app's own). A port held by anything else has nothing to stop.
 *  - [recordedPort] - the `rpcport` of the bitcoin.conf this app wrote for its
 *    last start - is the port being contacted. Core reads that file, and a
 *    daemon that cannot bind its configured RPC port exits, so this is the only
 *    endpoint a daemon started from it can be listening on.
 *  - there is a credential to present ([credentials]).
 *
 * The credential comes from the node's own files: Core's `.cookie` when the
 * data directory holds one, otherwise the pair this app generated for the start
 * the orphan came from, which is what its `rpcauth` line was built from. Only
 * the daemon that read that file can accept it, and the pair is replaced on
 * every start, so a foreign process that wins the port during the window
 * between the check and the bind learns a password that is already due to be
 * rotated - while the corrupted chainstate a SIGKILL can leave is not
 * recoverable on the phone at all.
 */
object OrphanStop {

    /** Largest cookie Core writes is a few dozen bytes; anything larger is not one. */
    private const val MAX_COOKIE_LENGTH = 512

    /**
     * `user:password` from Core's cookie text, or null when [text] is not one.
     *
     * The split is on the first colon: a password may contain one, a user name
     * does not (Core generates both from its own alphabet).
     */
    fun fromCookie(text: String?): RpcCredential? {
        val line = text?.trim().orEmpty()
        if (line.length > MAX_COOKIE_LENGTH) return null
        val separator = line.indexOf(':')
        if (separator <= 0 || separator == line.length - 1) return null
        val user = line.substring(0, separator)
        val password = line.substring(separator + 1)
        if (user.isBlank() || password.isBlank()) return null
        return RpcCredential(user, password)
    }

    /**
     * The pairs to try, best first: the cookie, then the stored pair.
     *
     * Normally only the stored pair is present - PocketCore always writes
     * `rpcauth`, so Core writes no cookie - but a data directory left by a build
     * or a configuration without it may still carry one, and then the cookie is
     * the credential the daemon on that directory actually answers to.
     */
    fun credentials(cookie: String?, user: String, password: String): List<RpcCredential> {
        val pairs = ArrayList<RpcCredential>(2)
        fromCookie(cookie)?.let(pairs::add)
        if (user.isNotBlank() && password.isNotBlank()) {
            val stored = RpcCredential(user, password)
            if (stored !in pairs) pairs.add(stored)
        }
        return pairs
    }

    /**
     * Whether the stop RPC may be sent to [port] at all - see the class comment
     * for the three facts. Fail-closed: anything not established is a refusal,
     * and the caller falls back to the kill it used before.
     */
    fun maySend(
        ownDaemonPids: List<Int>,
        port: Int,
        recordedPort: Int?,
        credentials: List<RpcCredential>
    ): Boolean = ownDaemonPids.isNotEmpty() &&
        port in 1..65535 &&
        recordedPort != null &&
        recordedPort == port &&
        credentials.isNotEmpty()
}
