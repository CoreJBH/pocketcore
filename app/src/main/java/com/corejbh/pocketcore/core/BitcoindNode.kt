package com.corejbh.pocketcore.core

import android.content.Context
import com.corejbh.pocketcore.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the bitcoind child process.
 *
 * The executable ships inside the APK as jniLibs/arm64-v8a/libbitcoind.so and
 * is extracted to nativeLibraryDir at install time, which is one of the few
 * places on modern Android where an app may execute its own native binary.
 *
 * The daemon is started with the RPC and P2P ports on the command line, because
 * `InitHTTPServer()` treats a busy RPC port as a fatal error and exits before
 * the chain is even opened.
 */
class BitcoindNode(private val context: Context) {

    /**
     * Written by the coroutine that starts or stops the daemon and read by the
     * poll loop and the UI, so the field is volatile: without it a reader can
     * keep seeing the process of an earlier start.
     */
    @Volatile private var process: Process? = null

    private companion object {
        /** `bitcoind -version` answers in milliseconds; this is a stuck binary. */
        const val VERSION_TIMEOUT_SECONDS = 5L

        /**
         * What the cached verdicts (integrity, version) belong to: this build.
         *
         * A new versionCode means the APK, and with it the extracted daemon,
         * may have changed, so nothing cached by the previous build is reused.
         * The expected hash is part of the tag as well: a rebuild that ships a
         * different daemon without bumping the versionCode still re-verifies.
         */
        val BUILD_TAG = "${BuildConfig.VERSION_CODE} ${BuildConfig.BITCOIND_SHA256}"
    }

    val binary: File get() = NodePaths.processBinaryPath(context)

    fun isAlive(): Boolean = process?.isAlive == true

    fun pid(): Int? {
        val current = process ?: return null
        if (!current.isAlive) return null
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        val entries = File("/proc").listFiles { file -> file.name.all(Char::isDigit) } ?: return null
        var fallback: Int? = null
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            // Only a process of this app may be described as its node: another
            // app's daemon with the same binary name is not ours to report or to
            // touch.
            val status = runCatching { File(entry, "status").readLines() }.getOrNull() ?: continue
            if (!ProcScan.runsAs(status, myUid)) continue
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!ProcScan.runsDaemon(cmdline, NodePaths.BINARY_NAME)) continue
            if (fallback == null) fallback = pid
            val parent = status.firstOrNull { it.startsWith("PPid:") }
                ?.filter(Char::isDigit)?.toIntOrNull()
            if (parent == myPid) return pid
        }
        return fallback
    }

    /**
     * Returns a human-readable problem, or null when the binary is usable.
     *
     * The extracted file is hashed against the checksum this APK was built with
     * before anything is executed: the executable bit in nativeLibraryDir is
     * what makes a replaced file dangerous, and the hash is what makes the
     * replacement visible.
     */
    fun prepareBinary(): String? {
        val file = binary
        if (!file.exists() || file.length() < 1_000_000) {
            return "Bundled bitcoind is missing at ${file.absolutePath}."
        }
        val expected = BuildConfig.BITCOIND_SHA256
        if (!BinaryIntegrity.isDigest(expected)) {
            // An APK built without the daemon hash has nothing to compare with,
            // and running the binary unchecked is exactly what this check
            // exists to prevent.
            return "This build carries no checksum for the bundled bitcoind, so it is not started."
        }
        val intact = BinaryIntegrity.verify(
            file = file,
            expectedSha256 = expected,
            stampFile = NodePaths.binaryIntegrityStamp(context),
            buildTag = BUILD_TAG
        )
        if (!intact) {
            return "The bundled bitcoind does not match its checksum " +
                "(${expected.take(12)}...). Reinstall PocketCore: the file at ${file.absolutePath} " +
                "is not the daemon this build shipped."
        }
        if (!file.canExecute()) {
            // Owner-only: the executable bit is for this app's UID, not for every
            // user on the device.
            file.setExecutable(true, true)
        }
        if (!file.canExecute()) {
            return "Bundled bitcoind is not executable at ${file.absolutePath}."
        }
        return null
    }

    /**
     * Starts the daemon in the background. Returns an error message or null.
     *
     * [network] is the chain this start runs: its debug.log is the evidence
     * file for the listener fallback ([ListenerFallback]), and only the bytes
     * written after this moment may vouch for this start's bind.
     *
     * The RPC and P2P ports live in bitcoin.conf, which [NodeController] has
     * just written with the endpoints it verified as free. They are deliberately
     * not repeated on the command line: Core merges list options such as
     * -rpcbind, so passing it twice would make the daemon try to bind the same
     * endpoint twice and log a misleading "already running" warning.
     */
    fun start(network: BitcoinNetwork): String? {
        prepareBinary()?.let { return it }
        // No killStrayProcesses() here. [NodeController] stops a daemon of ours
        // through the stop RPC before it asks for a start, and a hard kill of a
        // live daemon can leave its LevelDB files half written. A stray that
        // appears after that check makes Core refuse the datadir lock, which the
        // app reports; it is not worth an unclean shutdown to avoid.
        NodePaths.processLog(context).writeText("")
        // The bind evidence starts here: everything debug.log already holds was
        // written by earlier runs and must not vouch for this start.
        val evidenceLog = NodePaths.debugLog(context, network)
        bindEvidenceLog = evidenceLog
        bindEvidenceOffset = if (evidenceLog.isFile) evidenceLog.length() else 0L
        // The same moment, in wall-clock terms: if Core shrinks the log at startup
        // the offset points past the end of the file, the read falls back to the
        // file's start (see [NodePaths.readFromWindow]), and the preserved lines of
        // the run before have to be told apart from this run's by their timestamp.
        bindEvidenceStartedAt = System.currentTimeMillis()

        val args = listOf(
            binary.absolutePath,
            "-datadir=${NodePaths.dataDir(context).absolutePath}",
            "-conf=${NodePaths.confFile(context).absolutePath}",
            "-printtoconsole=0"
        )
        return try {
            val builder = ProcessBuilder(args)
            builder.directory(NodePaths.root(context))
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(NodePaths.processLog(context)))
            builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
            process = builder.start()
            null
        } catch (error: Exception) {
            "Could not start bitcoind: ${error.message}"
        }
    }

    /** Waits for a clean exit after the stop RPC has been sent. */
    fun awaitExit(seconds: Long): Boolean {
        val current = process ?: return true
        return try {
            current.waitFor(seconds, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun destroy() {
        runCatching { process?.destroy() }
        process = null
    }

    /**
     * The app process can die while the daemon keeps running as an orphan.
     * Such a stray instance still holds the datadir lock and the RPC port, so
     * it is terminated before a new one starts.
     *
     * Every kill is limited to processes of this app's UID (see [ProcScan]):
     * another node app's daemon is not ours to touch.
     *
     * The `.lock` file is deliberately left alone: it is a POSIX record lock, a
     * leftover file is harmless, and deleting it while a live daemon still owns
     * it lets a second daemon start on the same data directory.
     */
    fun killStrayProcesses(exclude: Int? = null): List<Int> {
        val pids = ownDaemonPids(exclude)
        val killed = ArrayList<Int>(pids.size)
        for (pid in pids) {
            if (runCatching { android.os.Process.killProcess(pid) }.isSuccess) killed.add(pid)
        }
        if (killed.isNotEmpty()) {
            // Give the kernel a moment to release the listening sockets.
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && killed.any { isAlivePid(it) }) {
                Thread.sleep(100)
            }
        }
        return killed
    }

    /**
     * PIDs of daemon processes running under this app's UID, including an orphan
     * left behind by a killed app process and excluding this process itself.
     */
    fun ownDaemonPids(exclude: Int? = null): List<Int> {
        val skip = buildSet {
            add(android.os.Process.myPid())
            if (exclude != null) add(exclude)
        }
        return ProcScan.daemonPids(
            procRoot = File("/proc"),
            binaryName = NodePaths.BINARY_NAME,
            uid = android.os.Process.myUid(),
            exclude = skip
        )
    }

    /** True when a daemon of this app is already running somewhere on the phone. */
    fun ownDaemonRunning(): Boolean = ownDaemonPids().isNotEmpty()

    /**
     * Waits up to [seconds] for every pid in [pids] to be gone.
     *
     * A stop RPC is answered before the daemon has finished: Core closes its
     * RPC server early in the shutdown and then writes the chain state out,
     * which on a phone takes seconds. The socket being free therefore says
     * nothing about the data being safe, and this is what the caller waits on
     * before deciding that a graceful stop failed and a kill is all that is
     * left.
     */
    fun awaitPidExit(pids: List<Int>, seconds: Long): Boolean {
        if (pids.isEmpty()) return true
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < deadline) {
            if (pids.none { isAlivePid(it) }) return true
            try {
                Thread.sleep(100)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return pids.none { isAlivePid(it) }
            }
        }
        return pids.none { isAlivePid(it) }
    }

    /**
     * True while [pid] is a daemon of this app.
     *
     * The binary name and the app's UID are both required: a PID that was reused
     * by another app's process must not be reported as this app's daemon, and
     * must never be signalled.
     */
    private fun isAlivePid(pid: Int): Boolean {
        val dir = File("/proc/$pid")
        if (!dir.exists()) return false
        val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return false
        if (!ProcScan.runsAs(status, android.os.Process.myUid())) return false
        return runCatching { File(dir, "cmdline").readBytes().toString(Charsets.UTF_8) }
            .getOrNull()?.let { ProcScan.runsDaemon(it, NodePaths.BINARY_NAME) } == true
    }

    /**
     * Wall-clock epoch millis at which [pid] started, or null when /proc does not
     * say.
     *
     * The kernel reports the start as clock ticks after boot, so the boot moment
     * is [nowMillis] minus the phone's uptime at the same instant. This is what
     * dates an orphan daemon: a `Binding RPC` line in its debug.log counts as
     * evidence for it only when the line was written after the process began, and
     * a line the previous run wrote cannot pass that test (see [OrphanStop]).
     */
    fun processStartedAt(
        pid: Int,
        nowMillis: Long = System.currentTimeMillis(),
        uptimeMillis: Long = android.os.SystemClock.elapsedRealtime()
    ): Long? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val ticks = ProcScan.statStartTicks(stat) ?: return null
        return ProcScan.processStartMillis(ticks, nowMillis, uptimeMillis)
    }

    /**
     * Asks daemons of this app to shut down with SIGTERM and waits for them.
     *
     * For Core that signal is the same clean shutdown as the `stop` RPC: it stops
     * the node, writes the chainstate out and exits, which is exactly what a
     * SIGKILL in the middle of a LevelDB write does not do. It needs no
     * credential and cannot reach anything but a process of this app's UID
     * running the bundled daemon ([isAlivePid] before every signal), so it is the
     * route left when the endpoint cannot be proved to be the daemon that is
     * listening on it.
     *
     * Returns true only once every pid in [pids] is gone.
     */
    fun terminateGracefully(pids: List<Int>, seconds: Long): Boolean {
        if (pids.isEmpty()) return true
        var signalled = false
        for (pid in pids) {
            if (!isAlivePid(pid)) continue
            val sent = runCatching {
                android.system.Os.kill(pid, android.system.OsConstants.SIGTERM)
            }.isSuccess
            if (sent) signalled = true
        }
        if (!signalled) return pids.none { isAlivePid(it) }
        return awaitPidExit(pids, seconds)
    }

    /**
     * Who holds the listening socket on [rpcPort], from [pid]'s own /proc entry.
     *
     * The app sends its RPC credentials to that endpoint, and the credential
     * pair is the only thing standing between the phone and a process that won
     * the port between the free-port check and the daemon's bind. Reading the
     * socket table and the fd links of the daemon's PID answers the question the
     * port probe cannot: is the listener the process that was just started?
     *
     * [SocketOwner.UNKNOWN] means the question could not be answered - no pid, a
     * /proc entry that cannot be read, a socket table that is missing. Callers
     * that transmit the password treat it as "not proven to be ours" and refuse
     * (see `NodeController.maySendCredentials`): an unreadable /proc must not
     * become a channel for the credentials.
     *
     * [probe] is the loopback connect used only when the daemon's namespace shows
     * no listener at all: if the port answers anyway, the listener sits in
     * another network namespace and is not the daemon's.
     */
    fun listenerOwner(
        rpcPort: Int,
        pid: Int? = pid(),
        probe: (Int) -> Boolean = PortProbe::isListening
    ): SocketOwner {
        val target = pid ?: return SocketOwner.UNKNOWN
        val dir = File("/proc/$target")
        if (!dir.exists()) return SocketOwner.UNKNOWN
        val tables = FILE_TABLES.map { name ->
            runCatching { File(dir, name).readText() }.getOrNull()
        }
        return when (val owner = ProcScan.listenerOwner(tables, File(dir, "fd"), rpcPort)) {
            SocketOwner.NOT_LISTENING ->
                if (probe(rpcPort)) SocketOwner.FOREIGN else SocketOwner.NOT_LISTENING
            SocketOwner.UNKNOWN -> fallbackOwner(rpcPort, probe)
            else -> owner
        }
    }

    private val FILE_TABLES = listOf("net/tcp", "net/tcp6")

    /** debug.log file and byte offset this start's bind evidence begins at. */
    @Volatile private var bindEvidenceLog: File? = null
    @Volatile private var bindEvidenceOffset: Long = 0L

    /** Wall-clock moment of this start's [bindEvidenceOffset]. */
    @Volatile private var bindEvidenceStartedAt: Long = 0L

    /**
     * The verdict when /proc could not answer ([SocketOwner.UNKNOWN]).
     *
     * On Android 10 and above SELinux refuses `/proc/<pid>/net/tcp` to every
     * app, so this is the normal case on a modern phone - the node of the
     * previous build would never leave STARTING. The owner is instead
     * established from the daemon being alive, the port answering on loopback
     * and this start's debug.log (see [ListenerFallback]); a port that answers
     * without that evidence stays FOREIGN, and UNKNOWN stays a refusal.
     *
     * The fallback rests on the daemon this process started ([isAlive]): an
     * orphan left by an earlier app process has no bind evidence in this
     * process's hands, so it stays UNKNOWN and is refused the credentials -
     * killed ungracefully instead, as in the previous build.
     */
    private fun fallbackOwner(rpcPort: Int, probe: (Int) -> Boolean): SocketOwner {
        val log = bindEvidenceLog
        val confirmed = if (log == null) {
            false
        } else {
            val window = NodePaths.readFromWindow(log, bindEvidenceOffset)
            ListenerFallback.rpcBindConfirmed(
                logWindow = window.text,
                port = rpcPort,
                // Only a window taken from the start of a shrunken file holds
                // lines an earlier run wrote, so only there does the bind have to
                // be stamped at or after this start: a preserved `Binding RPC`
                // line of the previous run, on this very port, would otherwise
                // vouch for a port that another app has since taken.
                sinceMillis = if (window.fromStartAfterShrink) bindEvidenceStartedAt else null
            )
        }
        return ListenerFallback.owner(
            alive = isAlive(),
            portOpen = probe(rpcPort),
            bindConfirmed = confirmed
        )
    }

    /**
     * Version reported by the bundled binary, e.g. "v32.1.0".
     *
     * The cached answer carries the build tag on its first line (see
     * [BUILD_TAG]): an updated app ships a possibly different daemon, and the
     * version of the previous one must not survive the update.
     */
    fun version(): String {
        val cached = NodePaths.binaryVersionStamp(context)
        if (cached.exists()) {
            val lines = runCatching { cached.readLines() }.getOrDefault(emptyList())
            if (lines.firstOrNull()?.trim() == BUILD_TAG) {
                val text = lines.getOrNull(1)?.trim().orEmpty()
                if (VersionProbe.isVersion(text)) return text
            }
            // Either an older build's entry or the daemon's error line instead
            // of a version: it is no longer trusted.
            runCatching { cached.delete() }
        }
        val detected = runCatching {
            val builder = ProcessBuilder(
                VersionProbe.command(binary.absolutePath, NodePaths.root(context))
            )
            builder.redirectErrorStream(true)
            builder.environment().putAll(
                VersionProbe.environment(builder.environment(), context.filesDir, context.cacheDir)
            )
            val proc = builder.start()
            // `-version` prints its line and exits. A binary that does not is a
            // broken one: the wait is bounded and ends it, instead of leaving a
            // child behind on every version lookup.
            val finished = runCatching { proc.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(true)
            if (!finished) proc.destroyForcibly()
            // The pipe is drained after the process ended, so a full pipe buffer
            // can no longer hold the reader.
            val output = runCatching {
                proc.inputStream.bufferedReader().use { reader -> reader.readText() }
            }.getOrDefault("")
            if (!finished) null else VersionProbe.parse(output)
        }.getOrNull()
        if (!detected.isNullOrBlank()) {
            runCatching { cached.writeText("$BUILD_TAG\n$detected\n") }
        }
        return detected.orEmpty()
    }

    /**
     * Last lines the daemon wrote to stdout/stderr.
     *
     * The window is read through [NodePaths.readTail] rather than with
     * `File.readLines()`: that call loads the whole file into one string, and
     * the process log of a daemon that keeps talking (a long sync, a chainstate
     * rebuild) would be allocated in full on the phone's heap just to show the
     * last few lines.
     */
    fun outputTail(maxLines: Int = 40): String = NodePaths.readTail(NodePaths.processLog(context), maxLines)

    /** Last lines of bitcoind's own debug.log for the configured network. */
    fun debugLogTail(network: BitcoinNetwork, maxLines: Int = 400): String =
        NodePaths.readTail(NodePaths.debugLog(context, network), maxLines)

    /**
     * Best available explanation for why the daemon exited.
     *
     * bitcoind prints the actionable detail ("Unable to bind to 127.0.0.1:8332
     * on this computer. Bitcoin Core is probably already running.") to
     * debug.log and only a generic sentence to the console, so debug.log wins.
     */
    fun describeExit(network: BitcoinNetwork): String {
        val fromLog = debugLogTail(network, 160).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // Most specific first: a bind collision is what the user can act on, and
        // Core prints the generic "Unable to start HTTP server" after it.
        val keys = listOf(
            "Unable to bind",
            "Cannot obtain a lock",
            "Unable to listen",
            "Error: Disk space",
            "Unable to start HTTP server",
            "Error:",
            "out of memory"
        )
        for (key in keys) {
            val match = fromLog.lastOrNull { it.contains(key, true) }
            if (match != null) return cleanLogLine(match)
        }
        val fromProcess = outputTail(30).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        if (fromProcess != null) return cleanLogLine(fromProcess)
        return "bitcoind exited without a message."
    }

    /** Drops the "2026-11-20T10:42:50Z [warning]" prefix from a debug.log line. */
    fun cleanLogLine(line: String): String {
        var text = line.trim()
        if (text.length > 21 && text[4] == '-' && text[10] == 'T') {
            text = text.substringAfter(' ', text)
        }
        text = text.removePrefix("[warning]").removePrefix("[error]").removePrefix("[info]").trim()
        return text
    }
}
