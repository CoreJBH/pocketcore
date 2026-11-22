package com.corejbh.pocketcore.core

import java.util.Locale
import kotlin.math.roundToLong
/**
 * Whether the phone has room for a snapshot before any of it is written.
 *
 * A UTXO snapshot is a few gigabytes. Without this check the import copies
 * hundreds of megabytes into a `.part` file and only then runs out of space, and
 * the load makes Core start writing a chainstate it cannot finish - both after
 * the user waited, and both leaving a half file behind. The check is a
 * [NodePaths.freeSpaceBytes] reading against the size the source reports, with a
 * reserve kept free so the node's own writes (the datadir, the log) do not take
 * the last bytes.
 *
 * The numbers are only ever compared as [Long]s, and an unknown free space (-1,
 * a StatFs that failed) never blocks: a check that cannot be made is not a
 * refusal, and the copy still fails cleanly on a full volume.
 */
internal object StorageBudget {

    /**
     * Bytes left free beside the copy.
     *
     * Loading the snapshot makes Core write a chainstate, and the daemon keeps
     * writing its log and its block files meanwhile; a volume filled to the last
     * byte would fail those writes after the user already paid for the copy.
     */
    const val RESERVE_BYTES = 64L * 1024 * 1024

    /** Bytes that have to be free to hold [sourceBytes] and keep the reserve. */
    fun requiredBytes(sourceBytes: Long): Long {
        if (sourceBytes <= 0L) return 0L
        return if (sourceBytes > Long.MAX_VALUE - RESERVE_BYTES) Long.MAX_VALUE
        else sourceBytes + RESERVE_BYTES
    }

    /**
     * True when [availableBytes] can hold [sourceBytes].
     *
     * [availableBytes] below zero is the unknown reading, and it is not a
     * refusal; a [sourceBytes] of zero or less means the size is unknown as
     * well, so there is nothing to compare.
     */
    fun fits(availableBytes: Long, sourceBytes: Long): Boolean {
        if (availableBytes < 0L || sourceBytes <= 0L) return true
        return availableBytes >= requiredBytes(sourceBytes)
    }

    /**
     * The sentence the card shows when a snapshot does not fit: what it needs,
     * what the phone has, and how much has to be freed - in that order, so the
     * user can act on it without arithmetic of their own.
     */
    fun shortageMessage(sourceBytes: Long, availableBytes: Long, action: String = "copy"): String {
        val need = requiredBytes(sourceBytes)
        val shortage = (need - availableBytes).coerceAtLeast(0L)
        return "Not enough free space to $action this snapshot: it needs ${humanBytes(need)} " +
            "and the phone has ${humanBytes(availableBytes)} free. Free up ${humanBytes(shortage)} " +
            "and try again."
    }

    /** Bytes in the units the rest of the app shows storage in. */
    fun humanBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        val text = if (value >= 100.0 || index == 0) {
            value.roundToLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$text ${units[index]}"
    }
}
