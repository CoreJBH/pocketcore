package com.corejbh.pocketcore.core

import java.time.Instant

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
 *
 * `-shrinkdebugfile=1` is the one case where the offset cannot be used as it
 * stands: Core truncates the log at startup, so the file becomes shorter than
 * the offset this start recorded. The read then starts at 0 instead (see
 * [NodePaths.readFromWindow]) - otherwise the window would be empty, the bind
 * line this start writes just after the shrink would never be seen, and a
 * healthy node would be reported as [SocketOwner.FOREIGN] and killed.
 *
 * Starting at 0 brings back the earlier runs' lines, though: the tail Core keeps
 * can hold the `Binding RPC` line of the run before, on the very same port. That
 * line is not evidence for this start - it says a listener *was* bound, not that
 * this daemon holds it now - so in that window [rpcBindConfirmed] also requires
 * a line timestamp at or after this start ([sinceMillis]). A preserved line from
 * a previous run is stamped before the start; the bind line of the daemon that
 * is starting now is stamped after it.
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
     *
     * [sinceMillis] is the moment the start whose daemon is being judged began.
     * It is passed when the window had to be read from the beginning of a
     * shrunken log (see the class comment) and is then required of every
     * candidate line: a line stamped before this start belongs to an earlier
     * run, and one that carries no timestamp can not be shown to belong to this
     * one. A null [sinceMillis] means the window itself already begins at the
     * start's byte offset, so every line in it was written by this run.
     *
     * The comparison is at whole seconds because that is Core's stamp
     * resolution: a line written in the same second as the start is this
     * start's own, and is accepted.
     */
    fun rpcBindConfirmed(logWindow: String, port: Int, sinceMillis: Long? = null): Boolean {
        val sinceSecond = sinceMillis?.let(::wholeSecond)
        for (line in logWindow.lineSequence()) {
            val match = BIND_LINE.find(line) ?: continue
            if (match.value.contains(BIND_FAILED, ignoreCase = true)) continue
            if (!isLoopback(match.groupValues[1])) continue
            if (match.groupValues[2].toIntOrNull() != port) continue
            if (sinceSecond == null) return true
            val stamped = lineTimestampMillis(line) ?: continue
            if (stamped >= sinceSecond) return true
        }
        return false
    }

    /** [millis] rounded down to the second, Core's stamp resolution. */
    private fun wholeSecond(millis: Long): Long = Math.floorDiv(millis, 1_000L) * 1_000L

    /**
     * Core's sentence when it binds the RPC server, host and port captured,
     * anchored at the line's end: the failure carries the same words followed
     * by ` failed`, and must not match.
     */
    private val BIND_LINE = Regex(
        "Binding RPC on address ([^\\s]+) port (\\d+)\\s*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /**
     * The timestamp Core prefixes to every debug.log line: ISO-8601 UTC, one
     * second resolution (`2026-11-22T05:12:09Z`). A line without one is not
     * datable, and a line that cannot be dated cannot be this start's.
     */
    private val LINE_TIMESTAMP = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)")

    /** Epoch millis of the stamp at the start of [line], or null when it has none. */
    private fun lineTimestampMillis(line: String): Long? {
        val stamp = LINE_TIMESTAMP.find(line)?.groupValues?.get(1) ?: return null
        return runCatching { Instant.parse(stamp).toEpochMilli() }.getOrNull()
    }

    private const val BIND_FAILED = "failed"

    private fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host == "[::1]" || host.equals("localhost", true)
}
