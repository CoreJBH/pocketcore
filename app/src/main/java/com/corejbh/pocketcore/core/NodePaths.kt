package com.corejbh.pocketcore.core

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Layout of everything the node owns on disk.
 *
 * <files>/node/
 *   bitcoin.conf          generated configuration (rewritten on every start)
 *   data/                 bitcoind datadir (chain data, debug.log, .lock)
 *   snapshots/            AssumeUTXO snapshot files copied in by the user
 *   bitcoind-output.log   stdout/stderr of the daemon process
 */
object NodePaths {

    fun root(context: Context): File = File(context.filesDir, "node").applyDir()

    fun dataDir(context: Context): File = File(root(context), "data").applyDir()

    fun confFile(context: Context): File = File(root(context), "bitcoin.conf")

    fun snapshotDir(context: Context): File = File(root(context), "snapshots").applyDir()

    fun processLog(context: Context): File = File(root(context), "bitcoind-output.log")

    fun debugLog(context: Context, network: BitcoinNetwork): File {
        val prefix = if (network.dataSubdir.isEmpty()) "" else "${network.dataSubdir}/"
        return File(dataDir(context), "${prefix}debug.log")
    }

    fun processBinaryPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    fun binaryVersionStamp(context: Context): File = File(root(context), "binary-version.txt")

    /**
     * Snapshot files in [dir] that [keep] does not name - the ones a new import
     * replaces, and the `.part` copy a killed import left behind.
     *
     * A snapshot is gigabytes, so keeping every file ever imported fills the
     * volume for data nothing points at. Only the file in use survives, and the
     * decision is pure so the selection is unit tested.
     */
    fun staleSnapshotFiles(dir: File, keep: File?): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { file ->
            val snapshot = file.name.endsWith(".dat") || file.name.endsWith(".dat.part")
            snapshot && file.isFile && file.absolutePath != keep?.absolutePath
        }
    }

    /**
     * The directory that holds one network's chain data.
     *
     * Mainnet is the data directory itself (`blocks/`, `chainstate/`,
     * `debug.log`), while signet and testnet4 have their own subdirectory beside
     * it.
     */
    fun networkDataDir(context: Context, network: BitcoinNetwork): File {
        val dir = dataDir(context)
        return if (network.dataSubdir.isEmpty()) dir else File(dir, network.dataSubdir)
    }

    /**
     * Bytes the selected network's chain occupies.
     *
     * For mainnet the other networks' subdirectories are skipped: they live in
     * the same parent directory, and counting them made the STORAGE card report
     * a size that no stored chain on screen could explain.
     */
    fun networkDataSizeBytes(context: Context, network: BitcoinNetwork): Long {
        val dir = networkDataDir(context, network)
        if (network.dataSubdir.isNotEmpty()) return dir.directorySize()
        val children = dir.listFiles() ?: return 0L
        return children
            .filterNot { it.isDirectory && it.name in OTHER_NETWORK_SUBDIRS }
            .sumOf { it.directorySize() }
    }

    /** Subdirectories of the data directory that belong to another network. */
    private val OTHER_NETWORK_SUBDIRS: Set<String> =
        BitcoinNetwork.entries.map { it.dataSubdir }.filter { it.isNotEmpty() }.toSet()

    /**
     * Where `dumptxoutset` writes before the file is handed to the user.
     *
     * It lives in the app's cache directory: the daemon runs as this app's UID,
     * writes `<path>.incomplete` beside it while it works, and nothing here has
     * to survive a failed export. The name carries the network and a timestamp,
     * so two exports never collide and Core never refuses an existing file.
     */
    fun snapshotExportFile(context: Context, network: BitcoinNetwork, stamp: Long): File =
        File(context.cacheDir, "utxo-${network.chainArg}-$stamp.dat")

    /** Bytes free on the volume that holds the chain, or -1 when unknown. */
    fun freeSpaceBytes(context: Context): Long = runCatching {
        android.os.StatFs(filesRoot(context).absolutePath).availableBytes
    }.getOrDefault(-1L)

    /**
     * The files and directories that hold [network]'s chain data.
     *
     * Mainnet keeps its chain in the data directory root, so its entries are
     * everything there except the other networks' subdirectories - and those
     * subdirectories are exactly what signet and testnet4 return. This is the
     * list a network-scoped wipe deletes, and it shows what such a wipe leaves
     * alone: another network's chain is never in it.
     */
    fun networkChainEntries(dataDir: File, network: BitcoinNetwork): List<File> {
        if (network.dataSubdir.isNotEmpty()) return listOf(File(dataDir, network.dataSubdir))
        val children = dataDir.listFiles() ?: return emptyList()
        return children.filterNot { it.isDirectory && it.name in OTHER_NETWORK_SUBDIRS }
    }

    /**
     * One read of a log from a byte offset.
     *
     * [text] is what could be read. [fromStartAfterShrink] is true when the file
     * had become shorter than the offset, so the read had to begin at the file's
     * start: the window then holds lines of earlier runs as well, and a caller
     * that treats a line as evidence for the current run has to check the line's
     * own timestamp (see [ListenerFallback.rpcBindConfirmed]).
     */
    class LogWindow(val text: String, val fromStartAfterShrink: Boolean)

    /**
     * Reads [file] from byte [offset], at most [maxBytes] of it, or "" when it
     * cannot be read.
     *
     * [readFromWindow] is the same read with the information about how it had to
     * be taken; this one is the text alone, for callers that need nothing else.
     */
    fun readFrom(file: File, offset: Long, maxBytes: Int = MAX_LOG_READ_BYTES): String =
        readFromWindow(file, offset, maxBytes).text

    /**
     * Reads [file] from byte [offset], at most [maxBytes] of it, and says whether
     * the read had to start at the file's beginning because the file had shrunk
     * under the offset.
     *
     * The reader of a log that is being written to uses an offset taken before
     * the write it cares about: bytes before the offset belong to earlier runs
     * and must not be read as evidence for this one (see [ListenerFallback]).
     *
     * The cap is what keeps the read bounded: debug.log grows into hundreds of
     * megabytes during a sync, and allocating "everything after the offset" in
     * one array either threw OutOfMemoryError or, past 2 GB, overflowed the
     * `Int` byte count. The window starts **at** the offset rather than at the
     * end of the file because the line being looked for is written right after
     * the offset: the bind evidence of a start sits in the first bytes of its
     * own window, and reading the tail instead would drop exactly that line.
     *
     * A file that is **shorter than the offset** was truncated, and the window
     * then starts at 0. Core rewrites debug.log at startup when
     * `-shrinkdebugfile=1` keeps it (it keeps the recent tail and drops the
     * rest), so the offset a start recorded can sit past the end of the file it
     * came from. Reading from the offset would return nothing at all, the bind
     * line written after the shrink would be missed, [ListenerFallback] would
     * call the port foreign and the node it just launched would be killed - a
     * healthy daemon taken for a stranger because its log got shorter.
     *
     * The kept tail is why such a window is reported as
     * [LogWindow.fromStartAfterShrink] and not as an ordinary read: it holds the
     * previous run's lines too, including the previous run's own `Binding RPC`
     * line on the same port, and only a timestamp check can tell the two apart.
     */
    fun readFromWindow(
        file: File,
        offset: Long,
        maxBytes: Int = MAX_LOG_READ_BYTES
    ): LogWindow {
        if (!file.exists()) return LogWindow("", false)
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val length = access.length()
                val shrunk = offset > length
                val start = if (shrunk) 0L else offset.coerceIn(0L, length)
                val wanted = minOf(length - start, maxBytes.coerceAtLeast(0).toLong()).toInt()
                access.seek(start)
                val bytes = ByteArray(wanted)
                access.readFully(bytes)
                LogWindow(String(bytes, Charsets.UTF_8), shrunk)
            }
        }.getOrDefault(LogWindow("", false))
    }

    /**
     * Core's cookie file for [network], in the data directory it was started in.
     *
     * Core writes `.cookie` only when no `rpcauth` / `rpcpassword` is configured,
     * so a daemon started from this app's bitcoin.conf does not create one. It
     * is read when a node of this app outlived the app process: a stop request
     * that can present an existing cookie is a cleaner end than a SIGKILL.
     */
    fun cookieFile(context: Context, network: BitcoinNetwork): File =
        File(networkDataDir(context, network), ".cookie")

    /** The cookie's text (`user:password`), or null when there is none. */
    fun readCookie(context: Context, network: BitcoinNetwork): String? {
        val file = cookieFile(context, network)
        if (!file.isFile) return null
        return runCatching {
            if (file.length() > MAX_COOKIE_BYTES) null else file.readText().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun filesRoot(context: Context): File = context.filesDir

    /**
     * Reads the last [maxLines] lines of a log without loading the whole file:
     * debug.log grows into tens of megabytes during a sync.
     */
    fun readTail(file: File, maxLines: Int): String {
        if (!file.exists()) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val length = access.length()
                val window = 256L * 1024L
                val start = maxOf(0L, length - window)
                access.seek(start)
                val bytes = ByteArray((length - start).toInt())
                access.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().takeLast(maxLines).joinToString("\n")
            }
        }.getOrDefault("")
    }

    private fun File.applyDir(): File {
        if (!exists()) mkdirs()
        return this
    }

    private fun File.directorySize(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        val children = listFiles() ?: return 0L
        return children.sumOf { it.directorySize() }
    }

    const val BINARY_NAME = "libbitcoind.so"

    /**
     * Upper bound of one log read.
     *
     * A megabyte is far more than the window a start's bind evidence or an exit
     * explanation needs, and small enough that the read cannot exhaust the heap
     * of an app that is already running a node.
     */
    const val MAX_LOG_READ_BYTES = 1024 * 1024

    /** Core's cookie is `user:password`; anything larger is not a cookie. */
    private const val MAX_COOKIE_BYTES = 4L * 1024L
}
