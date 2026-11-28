package com.corejbh.pocketcore.core

import java.io.File

/**
 * The P2P lines of bitcoind's `debug.log`, turned into [PulseEvent]s.
 *
 * The node's `net` and `mempool` log categories are turned on while the NODE
 * tab's pulse card is on screen ([NodeController.setPulseVisible]), so Core
 * writes one line per network message and one per transaction it accepts.
 * Those lines are the only place a P2P message exists at all: RPC offers
 * counters and peer totals, but no "who sent what, how big, and when".
 * Every line here was matched against output captured from the same Bitcoin
 * Core 32.1 the app bundles, so the card shows what the daemon logged rather
 * than a constructed example:
 *
 * ```
 * [net] received: inv (37 bytes) peer=0
 * [net] got inv: wtx <wtxid> new peer=0
 * [net] Requesting wtx <wtxid> peer=0
 * [net] received: tx (222 bytes) peer=0
 * [net] received getdata (1 invsz) peer=0
 * [net] received: pong (8 bytes) peer=0
 * [mempool] AcceptToMemoryPool: peer=0: accepted <txid> (wtxid=<wtxid>) (poolsz 1 txn, 1 kB)
 * UpdateTip: new best=<hash> height=101 version=0x20000000 ...
 * ```
 *
 * The parser carries the two facts that span lines: a `Requesting wtx` names
 * the transaction the next `received: tx` from that peer will carry, and that
 * message's size is what the later acceptance line is shown with.
 */
class PulseLogParser {

    private class Pending(val hash: String, val atMillis: Long)

    private class SizeMark(val bytes: Long, val atMillis: Long)

    /** The transaction this peer was asked for and has not delivered yet. */
    private val pending = HashMap<Int, Pending>()

    /** Sizes of delivered transactions, keyed by wtxid until Core accepts them. */
    private val sizes = HashMap<String, SizeMark>()

    fun reset() {
        pending.clear()
        sizes.clear()
    }

    /**
     * The events [lines] hold, oldest first.
     *
     * [atMillis] is the monotonic time the lines were read at: the log stamps
     * whole seconds, which is too coarse for the activity dot, and the read is
     * never more than one sample behind the write.
     */
    fun parse(lines: List<String>, atMillis: Long): List<PulseEvent> {
        if (lines.isEmpty()) return emptyList()
        prune(atMillis)
        val events = ArrayList<PulseEvent>()
        val invCounts = LinkedHashMap<Int, Int>()
        var acceptedBlock: PulseEvent? = null

        for (line in lines) {
            ACCEPTED.find(line)?.let { match ->
                val peerId = match.groupValues[1].toIntOrNull() ?: return@let
                val txid = match.groupValues[2]
                val wtxid = match.groupValues[3]
                val bytes = sizes.remove(wtxid)?.bytes
                events += PulseEvent(
                    kind = PulseKind.TX,
                    direction = PulseDirection.ACCEPTED,
                    peerId = peerId,
                    bytes = bytes,
                    hash = txid,
                    atMillis = atMillis
                )
                return@let
            }
            TIP.find(line)?.let { match ->
                acceptedBlock = PulseEvent(
                    kind = PulseKind.BLOCK,
                    direction = PulseDirection.ACCEPTED,
                    peerId = NO_PEER,
                    hash = match.groupValues[1],
                    blockHeight = match.groupValues[2].toLongOrNull(),
                    atMillis = atMillis
                )
                return@let
            }
            GOT_INV.find(line)?.let { match ->
                val kind = match.groupValues[1]
                val status = match.groupValues[3]
                val peerId = match.groupValues[4].toIntOrNull() ?: return@let
                // Block announcements are not counted as transactions; a block
                // shows up as the UpdateTip line once Core has connected it.
                if (kind != "block" && status == "new") {
                    invCounts[peerId] = (invCounts[peerId] ?: 0) + 1
                }
                return@let
            }
            REQUESTING.find(line)?.let { match ->
                val peerId = match.groupValues[3].toIntOrNull() ?: return@let
                pending[peerId] = Pending(match.groupValues[2], atMillis)
                events += PulseEvent(
                    kind = PulseKind.GETDATA,
                    direction = PulseDirection.OUT,
                    peerId = peerId,
                    atMillis = atMillis
                )
                return@let
            }
            GETDATA.find(line)?.let { match ->
                val peerId = match.groupValues[2].toIntOrNull() ?: return@let
                events += PulseEvent(
                    kind = PulseKind.GETDATA,
                    direction = PulseDirection.IN,
                    peerId = peerId,
                    count = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    atMillis = atMillis
                )
                return@let
            }
            RECEIVED.find(line)?.let { match ->
                val message = match.groupValues[1]
                val bytes = match.groupValues[2].toLongOrNull()
                val peerId = match.groupValues[3].toIntOrNull() ?: return@let
                when (message) {
                    "tx" -> {
                        // The size belongs to the transaction Core just
                        // delivered, so it is remembered under the hash the
                        // request named and read back at the acceptance line.
                        val asked = pending.remove(peerId) ?: return@let
                        if (bytes != null) sizes[asked.hash] = SizeMark(bytes, atMillis)
                    }
                    "pong" -> events += PulseEvent(
                        kind = PulseKind.PONG,
                        direction = PulseDirection.ROUND_TRIP,
                        peerId = peerId,
                        bytes = bytes,
                        atMillis = atMillis
                    )
                    "ping" -> events += PulseEvent(
                        kind = PulseKind.PING,
                        direction = PulseDirection.IN,
                        peerId = peerId,
                        bytes = bytes,
                        atMillis = atMillis
                    )
                }
                return@let
            }
            SENDING.find(line)?.let { match ->
                if (match.groupValues[1] == "ping") {
                    val peerId = match.groupValues[3].toIntOrNull() ?: return@let
                    events += PulseEvent(
                        kind = PulseKind.PING,
                        direction = PulseDirection.OUT,
                        peerId = peerId,
                        bytes = match.groupValues[2].toLongOrNull(),
                        atMillis = atMillis
                    )
                }
                return@let
            }
        }

        for ((peerId, count) in invCounts) {
            events += PulseEvent(
                kind = PulseKind.INV,
                direction = PulseDirection.IN,
                peerId = peerId,
                count = count,
                atMillis = atMillis
            )
        }
        acceptedBlock?.let { events += it }
        return events
    }

    private fun prune(atMillis: Long) {
        val cutoff = atMillis - PENDING_LIFETIME_MILLIS
        pending.entries.removeIf { it.value.atMillis < cutoff }
        sizes.entries.removeIf { it.value.atMillis < cutoff }
        if (sizes.size > MAX_SIZES) {
            sizes.keys.take(sizes.size - MAX_SIZES).forEach { sizes.remove(it) }
        }
    }

    private companion object {
        /** Blocks are not a peer's: they are the chain this node now has. */
        const val NO_PEER = -1

        const val PENDING_LIFETIME_MILLIS = 60_000L

        const val MAX_SIZES = 256

        val ACCEPTED = Regex(
            """AcceptToMemoryPool: peer=(-?\d+): accepted ([0-9a-fA-F]{64}) \(wtxid=([0-9a-fA-F]{64})\)"""
        )

        val TIP = Regex("""UpdateTip: new best=([0-9a-fA-F]{64}) height=(\d+)""")

        val GOT_INV = Regex("""got inv: (wtx|tx|block) ([0-9a-fA-F]{64}) (\S+) peer=(-?\d+)""")

        val REQUESTING = Regex("""Requesting (wtx|tx) ([0-9a-fA-F]{64}) peer=(-?\d+)""")

        val GETDATA = Regex("""received getdata \((\d+) invsz\) peer=(-?\d+)""")

        val RECEIVED = Regex("""received: (\w+) \((\d+) bytes\) peer=(-?\d+)""")

        val SENDING = Regex("""sending (\w+) \((\d+) bytes\) peer=(-?\d+)""")
    }
}

/**
 * Reads only what [DebugLogTail] has not read yet from a log that keeps growing.
 *
 * `debug.log` is written by another process while this reads it, is shrunk at
 * startup, and on a busy node gains tens of kilobytes a second. The tail
 * therefore keeps a byte offset and a half line rather than re-reading the
 * file: a leftover fragment is completed by the next read, and a file that
 * became shorter than the offset (Core shrank it, or it was rotated) is
 * skipped to its end instead of being replayed as if it were happening now.
 *
 * What the two costs are worth: an event log that shows the last minute is
 * what the card is for, so a very large backlog - the app was not sampling for
 * hours - is dropped and reading resumes at the file's tail rather than
 * spending the phone's CPU on lines no window can show.
 */
class DebugLogTail(file: File? = null) {

    /** Starts reading [file] from its end: lines written before now are history. */
    private val file: File? = file
    private var offset: Long = file?.length() ?: 0L
    private var carry = StringBuilder()

    /** The complete lines that appeared since the previous call, in order. */
    fun readNew(): List<String> {
        val current = file ?: return emptyList()
        if (!current.isFile) return emptyList()
        val length = runCatching { current.length() }.getOrDefault(0L)
        if (length < offset) {
            // Shrunk or replaced: what is in the file now is not this read's
            // news, and replaying it would draw an old minute as the present.
            offset = length
            carry = StringBuilder()
            return emptyList()
        }
        var pending = length - offset
        if (pending <= 0L) return emptyList()
        if (pending > SKIP_THRESHOLD_BYTES) {
            offset = (length - TAIL_BYTES).coerceAtLeast(0L)
            carry = StringBuilder()
        }
        val window = NodePaths.readFromWindow(current, offset, SKIP_THRESHOLD_BYTES.toInt())
        val text = window.text
        if (text.isEmpty()) return emptyList()
        offset += text.toByteArray(Charsets.UTF_8).size
        carry.append(text)
        if (carry.length > MAX_CARRY_CHARS) {
            // A line that long is not a P2P message line; keep the tail of it
            // and let the next read finish it.
            carry.delete(0, carry.length - MAX_CARRY_CHARS)
        }
        val combined = carry.toString()
        val parts = combined.split('\n')
        carry = StringBuilder(parts.last())
        return parts.dropLast(1).filter { it.isNotBlank() }
    }

    private companion object {
        /** More than this waiting means the sampler was away, not behind. */
        const val SKIP_THRESHOLD_BYTES = 256L * 1024L

        /** Where reading resumes when a backlog is dropped. */
        const val TAIL_BYTES = 64L * 1024L

        /** A fragment this large is not a line this parser reads. */
        const val MAX_CARRY_CHARS = 16_384
    }
}
