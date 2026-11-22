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
        return withReserve(sourceBytes)
    }

    /**
     * Bytes that have to be free to activate a snapshot with `loadtxoutset`.
     *
     * The older rule asked for the snapshot file's size plus the reserve, which
     * is the wrong quantity on two counts (versionCode 21):
     *
     *  - the file is **already on disk** when LOAD is pressed, so its bytes are
     *    not what the load still has to write;
     *  - the load writes a whole second chainstate (`chainstate_snapshot/`) and
     *    keeps the existing one beside it until background validation catches
     *    up, so the volume has to hold **both** at once.
     *
     * The new chainstate holds the same coins as the file, so the snapshot's
     * size is the floor of the estimate, and the chainstate already on this
     * phone is a measured second estimate of the same set in Core's leveldb
     * layout (at block 935,000 on mainnet the published 8.7 GiB snapshot loads
     * into a chainstate whose `disk_size` is 13.29 GB). The larger of the two is
     * taken, and the reserve still has to stay free for the daemon's own writes.
     *
     * Either figure may be unknown (-1, a provider that does not report a size
     * or a phone with no chainstate yet); the known one is then used alone.
     */
    fun requiredLoadBytes(snapshotBytes: Long, existingChainstateBytes: Long): Long {
        val estimate = maxOf(snapshotBytes, existingChainstateBytes)
        if (estimate <= 0L) return 0L
        return withReserve(estimate)
    }

    /**
     * True when [availableBytes] can hold the chainstate a load writes beside
     * the one already there ([requiredLoadBytes]).
     */
    fun fitsLoad(
        availableBytes: Long,
        snapshotBytes: Long,
        existingChainstateBytes: Long = -1L
    ): Boolean {
        if (availableBytes < 0L) return true
        val need = requiredLoadBytes(snapshotBytes, existingChainstateBytes)
        if (need == 0L) return true
        return availableBytes >= need
    }

    private fun withReserve(bytes: Long): Long =
        if (bytes > Long.MAX_VALUE - RESERVE_BYTES) Long.MAX_VALUE else bytes + RESERVE_BYTES

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

    /**
     * The sentence the card shows when a snapshot cannot be activated: the second
     * chainstate, the one already on disk, and what to free.
     *
     * The load message is separate from [shortageMessage] because the numbers are
     * different facts: the import is short of room for a copy, the load is short
     * of room for the chainstate that copy is replayed into, beside the existing
     * one (versionCode 21).
     */
    fun loadShortageMessage(
        snapshotBytes: Long,
        existingChainstateBytes: Long,
        availableBytes: Long
    ): String {
        val estimate = maxOf(snapshotBytes, existingChainstateBytes).coerceAtLeast(0L)
        val need = requiredLoadBytes(snapshotBytes, existingChainstateBytes)
        val shortage = (need - availableBytes).coerceAtLeast(0L)
        val beside = if (existingChainstateBytes > 0L) {
            "beside the ${humanBytes(existingChainstateBytes)} chainstate already on the phone"
        } else {
            "beside the chainstate already on the phone"
        }
        return "Not enough free space to activate this snapshot: Core writes a second chainstate of " +
            "about ${humanBytes(estimate)} $beside, so it needs ${humanBytes(need)} free and the " +
            "phone has ${humanBytes(availableBytes)}. Free up ${humanBytes(shortage)} and try again."
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
