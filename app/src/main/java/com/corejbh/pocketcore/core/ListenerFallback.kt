package com.corejbh.pocketcore.core

/**
 * Who holds the RPC listener when /proc cannot answer.
 *
 * Since Android 10, SELinux keeps an app from reading the per-process socket
 * tables (`/proc/<pid>/net/tcp`), so [ProcScan.listenerOwner] returns
 * [SocketOwner.UNKNOWN] on every modern device and a node that started fine
 * never leaves STARTING: the poll loop withholds the credentials from an
 * unproven port forever.
 *
 * The fallback establishes the owner from three facts instead, each of which a
 * stranger's process cannot arrange:
 *
 *  - the daemon this app launched is still alive, which also rules out a bind
 *    failure - Core exits when it cannot bind the RPC port;
 *  - the RPC port answers on loopback, so a listener exists;
 *  - the daemon's own debug.log, read from the byte offset this start began
 *    at, shows Core binding the RPC endpoint without the failure that Core
 *    logs when the bind did not happen.
 *
 * Reading only past this start's offset is what keeps the third fact honest: a
 * `Binding RPC` line from an earlier run of the same port cannot vouch for
 * this start. The credential pair is generated for one start alone, so a
 * stale line plus a port won by another process in the check-and-bind race -
 * where this daemon would have exited - cannot pass all three together. A
 * listener that answers without the evidence is [SocketOwner.FOREIGN], exactly
 * as if /proc had named it.
 */
object ListenerFallback {

    /**
     * The verdict from the three facts.
     *
     * [alive] is the daemon process, [portOpen] the loopback connect, and
     * [bindConfirmed] whether this start's debug.log shows Core binding the
     * RPC endpoint. [SocketOwner.UNKNOWN] when the process is gone: there is
     * nothing left to vouch for, and the caller's handling of a dead daemon
     * (an unexpected-exit report) is the better answer.
     */
    fun owner(alive: Boolean, portOpen: Boolean, bindConfirmed: Boolean): SocketOwner = when {
        !alive -> SocketOwner.UNKNOWN
        !portOpen -> SocketOwner.NOT_LISTENING
        bindConfirmed -> SocketOwner.OURS
        else -> SocketOwner.FOREIGN
    }

    /**
     * True when [logWindow] - the debug.log text written since this start -
     * shows Core binding the RPC listener on loopback [port].
     *
     * Core writes `Binding RPC on address 127.0.0.1 port 8332` when it binds
     * and the same sentence with ` failed` when the bind did not happen, so
     * the line is matched and the failure excluded. The cookie file is no
     * alternative here: it is only written when no rpcauth is configured, and
     * PocketCore always configures one.
     */
    fun rpcBindConfirmed(logWindow: String, port: Int): Boolean =
        BIND_LINE.findAll(logWindow).any { match ->
            !match.value.contains(BIND_FAILED, ignoreCase = true) &&
                match.groupValues[1].let(::isLoopback) &&
                match.groupValues[2].toIntOrNull() == port
        }

    /**
     * Core's sentence when it binds the RPC server, host and port captured,
     * anchored at the line's end: the failure carries the same words followed
     * by ` failed`, and must not match.
     */
    private val BIND_LINE = Regex(
        "Binding RPC on address ([^\\s]+) port (\\d+)\\s*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private const val BIND_FAILED = "failed"

    private fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host == "[::1]" || host.equals("localhost", true)
}
