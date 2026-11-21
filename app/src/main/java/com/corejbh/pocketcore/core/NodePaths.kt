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

    fun datadirSizeBytes(context: Context): Long = dataDir(context).directorySize()

    /** Bytes free on the volume that holds the chain, or -1 when unknown. */
    fun freeSpaceBytes(context: Context): Long = runCatching {
        android.os.StatFs(filesRoot(context).absolutePath).availableBytes
    }.getOrDefault(-1L)

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
