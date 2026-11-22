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
     * Records the SHA-256 verdict on [processBinaryPath] for one build, so the
     * extracted daemon is hashed once per install and version rather than on
     * every start.
     */
    fun binaryIntegrityStamp(context: Context): File = File(root(context), "binary-integrity.txt")

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
     * Reads [file] from byte [offset] to the end, or "" when it cannot be read.
     *
     * The reader of a log that is being written to uses an offset taken before
     * the write it cares about: bytes before the offset belong to earlier runs
     * and must not be read as evidence for this one (see [ListenerFallback]).
     */
    fun readFrom(file: File, offset: Long): String {
        if (!file.exists()) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { access ->
                val start = offset.coerceIn(0L, access.length())
                access.seek(start)
                val bytes = ByteArray((access.length() - start).toInt())
                access.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
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
}
