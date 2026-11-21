package com.jbitmstr.pocketcore.core

import android.content.Context
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

    private var process: Process? = null

    val binary: File get() = NodePaths.processBinaryPath(context)

    fun isAlive(): Boolean = process?.isAlive == true

    fun pid(): Int? {
        val current = process ?: return null
        if (!current.isAlive) return null
        val myPid = android.os.Process.myPid()
        val entries = File("/proc").listFiles { file -> file.name.all(Char::isDigit) } ?: return null
        var fallback: Int? = null
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val cmdline = runCatching {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (!ProcScan.runsBinary(cmdline, NodePaths.BINARY_NAME)) continue
            if (fallback == null) fallback = pid
            val parent = runCatching {
                File(entry, "status").readLines().firstOrNull { it.startsWith("PPid:") }
                    ?.filter(Char::isDigit)?.toIntOrNull()
            }.getOrNull()
            if (parent == myPid) return pid
        }
        return fallback
    }

    /** Returns a human-readable problem, or null when the binary is usable. */
    fun prepareBinary(): String? {
        val file = binary
        if (!file.exists() || file.length() < 1_000_000) {
            return "Bundled bitcoind is missing at ${file.absolutePath}."
        }
        if (!file.canExecute()) {
            file.setExecutable(true, false)
        }
        if (!file.canExecute()) {
            return "Bundled bitcoind is not executable at ${file.absolutePath}."
        }
        return null
    }

    /**
     * Starts the daemon in the background. Returns an error message or null.
     *
     * The RPC and P2P ports live in bitcoin.conf, which [NodeController] has
     * just written with the endpoints it verified as free. They are deliberately
     * not repeated on the command line: Core merges list options such as
     * -rpcbind, so passing it twice would make the daemon try to bind the same
     * endpoint twice and log a misleading "already running" warning.
     */
    fun start(): String? {
        prepareBinary()?.let { return it }
        killStrayProcesses()
        NodePaths.processLog(context).writeText("")

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

    private fun isAlivePid(pid: Int): Boolean = File("/proc/$pid").exists() &&
        runCatching { File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8) }
            .getOrNull()?.let { ProcScan.runsBinary(it, NodePaths.BINARY_NAME) } == true

    /** Version reported by the bundled binary, e.g. "v32.1.0". */
    fun version(): String {
        val cached = NodePaths.binaryVersionStamp(context)
        if (cached.exists()) {
            val text = cached.readText().trim()
            if (VersionProbe.isVersion(text)) return text
            // An earlier build could cache the daemon's error line here instead
            // of a version; it is no longer trusted.
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
            val output = proc.inputStream.bufferedReader().use { reader -> reader.readText() }
            proc.waitFor(5, TimeUnit.SECONDS)
            VersionProbe.parse(output)
        }.getOrNull()
        if (!detected.isNullOrBlank()) {
            runCatching { cached.writeText(detected) }
        }
        return detected.orEmpty()
    }

    /** Last lines the daemon wrote to stdout/stderr. */
    fun outputTail(maxLines: Int = 40): String {
        val file = NodePaths.processLog(context)
        if (!file.exists()) return ""
        return runCatching {
            file.readLines().takeLast(maxLines).joinToString("\n")
        }.getOrDefault("")
    }

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
