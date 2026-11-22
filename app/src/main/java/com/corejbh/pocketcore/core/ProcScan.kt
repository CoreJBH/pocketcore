package com.corejbh.pocketcore.core

import java.io.File
import java.nio.file.Files

/**
 * Who holds the listening socket on a loopback port the app is about to talk to.
 *
 * [UNKNOWN] is not a verdict: it means /proc could not be read at all, and the
 * caller has to decide what its best effort is.
 */
enum class SocketOwner { OURS, FOREIGN, NOT_LISTENING, UNKNOWN }

/**
 * The parts of /proc the app has to read before it touches a process.
 *
 * Android gives every app its own UID, and /proc entries of another UID are not
 * readable from here: a process this scan can see is a process of this app. The
 * two questions asked below decide whether a busy RPC port may be handed our
 * credentials, and which PIDs are strays of the bundled daemon.
 *
 * Plain Kotlin over [File] paths, so the parsing can be unit tested against a
 * fake proc directory.
 */
object ProcScan {

    /**
     * Real UID of a process, from its `status` lines.
     *
     * The `Uid:` line holds four values (real, effective, saved, filesystem).
     * The comparison is on whole tokens: a substring test made UID 10123 accept
     * a process of UID 101234.
     */
    fun realUid(statusLines: List<String>): Int? =
        realUidOfLine(statusLines.firstOrNull { it.startsWith("Uid:") })

    /** Real UID of a single `Uid:\t<real>\t<effective>\t<saved>\t<fs>` line. */
    fun realUidOfLine(line: String?): Int? {
        val values = line?.substringAfter(':', "")?.trim().orEmpty()
        if (values.isEmpty()) return null
        return values.split(WHITESPACE).firstOrNull()?.toIntOrNull()
    }

    /** True when [statusLines] belong to a process running under [uid]. */
    fun runsAs(statusLines: List<String>, uid: Int): Boolean = realUid(statusLines) == uid

    /**
     * True when one of the NUL separated argv entries of a process names
     * [binaryName]. The daemon is started by its absolute path, so the entry is
     * compared by file name.
     */
    fun runsBinary(cmdline: String, binaryName: String): Boolean =
        cmdline.split('\u0000').any { token ->
            token.isNotEmpty() && token.substringAfterLast('/') == binaryName
        }

    /**
     * True when a process is the daemon rather than the app's own `-version`
     * probe of the same binary. A probe is never the thing holding a port, and
     * killing it would only cost the version line on screen.
     */
    fun runsDaemon(cmdline: String, binaryName: String): Boolean =
        runsBinary(cmdline, binaryName) &&
            cmdline.split('\u0000').none { it == VERSION_FLAG }

    /** The socket state Linux writes for a listening TCP socket in /proc/net/tcp. */
    const val TCP_LISTEN = "0A"

    /**
     * Inodes of the listening sockets on [port] in a /proc/net/tcp or
     * /proc/net/tcp6 table.
     *
     * Columns are `sl local_address rem_address st ... inode`, with the port in
     * hex after the colon of the local address. Only the port is compared: Core
     * binds 127.0.0.1 for RPC, but a daemon told to bind [::1] appears in the
     * tcp6 table instead, and both mean the same endpoint to the app.
     */
    fun listenInodes(tcpTable: String, port: Int): Set<String> {
        val inodes = LinkedHashSet<String>()
        for (line in tcpTable.lineSequence().drop(1)) {
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < 10) continue
            if (fields[3] != TCP_LISTEN) continue
            val localPort = fields[1].substringAfterLast(':').toIntOrNull(16) ?: continue
            if (localPort != port) continue
            val inode = fields[9]
            if (inode.isNotEmpty()) inodes.add(inode)
        }
        return inodes
    }

    /** The inode of a `socket:[123]` fd link, or null for anything else. */
    fun socketInode(link: String): String? =
        if (link.startsWith(SOCKET_PREFIX) && link.endsWith("]")) {
            link.substring(SOCKET_PREFIX.length, link.length - 1).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /**
     * True when the fd table in [fdDir] holds a socket of one of [inodes].
     *
     * A socket inode alone says a socket exists somewhere in the network
     * namespace, not that this process owns it. The owning process has the
     * socket open, so its fd table links to it - that is the check the app needs
     * before it sends the daemon its RPC credentials.
     */
    fun ownsSocket(fdDir: File, inodes: Set<String>): Boolean {
        if (inodes.isEmpty()) return false
        val entries = fdDir.list() ?: return false
        for (name in entries) {
            val target = runCatching {
                Files.readSymbolicLink(File(fdDir, name).toPath()).toString()
            }.getOrNull() ?: continue
            val inode = socketInode(target) ?: continue
            if (inode in inodes) return true
        }
        return false
    }

    /**
     * Who owns the listening socket on [port], from one process's /proc entry.
     *
     * [tcpTables] are the texts of that process's `net/tcp` and `net/tcp6`; a
     * null entry is a table that could not be read. [fdDir] is its `fd`
     * directory. Reading the tables through `/proc/<pid>/net/` puts them in the
     * daemon's own network namespace, so a listener of another app - including
     * one behind a VPN - cannot be mistaken for the daemon's socket.
     */
    fun listenerOwner(tcpTables: List<String?>, fdDir: File, port: Int): SocketOwner {
        if (tcpTables.all { it == null }) return SocketOwner.UNKNOWN
        val inodes = tcpTables.filterNotNull().flatMap { listenInodes(it, port) }.toSet()
        if (inodes.isEmpty()) return SocketOwner.NOT_LISTENING
        if (!fdDir.isDirectory) return SocketOwner.UNKNOWN
        return if (ownsSocket(fdDir, inodes)) SocketOwner.OURS else SocketOwner.FOREIGN
    }

    /** The argument `BitcoindNode.version()` starts the binary with. */
    const val VERSION_FLAG = "-version"

    /**
     * Clock ticks per second that `/proc/<pid>/stat` counts in (Linux USER_HZ).
     *
     * Android's user-space clock is 100 Hz on every ABI the app ships, which is
     * what turns the `starttime` field into a duration.
     */
    const val CLOCK_TICKS_PER_SECOND = 100L

    /**
     * Field 22 of `/proc/<pid>/stat` - `starttime`, in clock ticks after boot -
     * or null when [stat] does not parse.
     *
     * The second field is the executable name in parentheses, and it may itself
     * contain spaces or parentheses (`(bitcoind (old))`), so the fields are
     * counted from after the last `)`. The first token there is field 3
     * (`state`), which makes `starttime` (field 22) token 19.
     */
    fun statStartTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim()
        if (fields.isEmpty()) return null
        return fields.split(WHITESPACE).getOrNull(STARTTIME_FIELD - 3)?.toLongOrNull()
    }

    /**
     * Wall-clock epoch millis at which a process with [startTicks] began.
     *
     * [nowMillis] is the wall clock now and [uptimeMillis] the phone's own up
     * time at the same moment (`SystemClock.elapsedRealtime()`): the boot moment
     * is the difference, and the daemon started [startTicks] ticks after it.
     */
    fun processStartMillis(startTicks: Long, nowMillis: Long, uptimeMillis: Long): Long =
        nowMillis - uptimeMillis + startTicks * 1_000L / CLOCK_TICKS_PER_SECOND

    /** Field number of `starttime` in `/proc/<pid>/stat`. */
    private const val STARTTIME_FIELD = 22

    /** Prefix of the `/proc/<pid>/fd` link that names a socket by inode. */
    const val SOCKET_PREFIX = "socket:["

    /**
     * PIDs under [procRoot] that run [binaryName] as [uid].
     *
     * With [uid] null the UID filter is skipped, which is only useful for tests.
     * Entries this process cannot read (another UID, a process that just died)
     * are skipped rather than treated as a match.
     */
    fun daemonPids(
        procRoot: File,
        binaryName: String,
        uid: Int?,
        exclude: Set<Int> = emptySet()
    ): List<Int> {
        val entries = procRoot.listFiles { file -> file.name.all(Char::isDigit) } ?: return emptyList()
        val pids = ArrayList<Int>(2)
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid in exclude) continue
            val cmdline = readText(File(entry, "cmdline")) ?: continue
            if (!runsDaemon(cmdline, binaryName)) continue
            if (uid != null) {
                val status = readLines(File(entry, "status")) ?: continue
                if (!runsAs(status, uid)) continue
            }
            pids.add(pid)
        }
        return pids
    }

    private fun readText(file: File): String? = runCatching { file.readBytes() }
        .getOrNull()
        ?.toString(Charsets.UTF_8)

    private fun readLines(file: File): List<String>? = runCatching { file.readLines() }.getOrNull()

    private val WHITESPACE = Regex("[\\s\u0000]+")
}
