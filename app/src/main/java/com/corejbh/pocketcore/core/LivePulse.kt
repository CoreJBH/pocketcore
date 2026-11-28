package com.corejbh.pocketcore.core

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

/**
 * The live P2P view of the NODE tab: what this node is sending and receiving
 * right now.
 *
 * Everything here is measured from the daemon, never generated: the byte
 * counters are `getnettotals` and `getpeerinfo` answers, and every event is a
 * line bitcoind itself wrote to `debug.log` (see [PulseLogParser]). A card
 * that drew a pretty curve out of nothing would be the one thing this app is
 * built not to do, so an idle node draws a flat line and an empty log.
 */
enum class PulseDirection {
    /** A message this node received from a peer. */
    IN,

    /** A message this node sent to a peer. */
    OUT,

    /** A ping and its pong, as one round-trip line. */
    ROUND_TRIP,

    /** Something Core checked and accepted: a transaction or a block. */
    ACCEPTED
}

/** The P2P message kinds the pulse log keeps. */
enum class PulseKind { TX, INV, GETDATA, PING, PONG, BLOCK }

/**
 * One real P2P event, as written by the daemon.
 *
 * [hash] is the transaction id of an accepted transaction or the block hash of
 * an accepted block; [bytes] is the message size Core logged for it; [pingMs]
 * is the peer's last measured round trip (`getpeerinfo` `pingtime`), attached
 * by the sampler that read the event, and is null when the peer is gone.
 */
data class PulseEvent(
    val kind: PulseKind,
    val direction: PulseDirection,
    val peerId: Int,
    val peerAddress: String = "",
    val bytes: Long? = null,
    val hash: String? = null,
    val blockHeight: Long? = null,
    val pingMs: Double? = null,
    val count: Int = 1,
    val atMillis: Long = 0L
)

/** One peer's share of the traffic, in bytes per second. */
data class PeerPulse(
    val peerId: Int,
    val address: String,
    val inBps: Double,
    val outBps: Double,
    /** When this peer last moved a byte or produced an event; 0 if never. */
    val lastActivityAt: Long
)

/**
 * A snapshot of the pulse card.
 *
 * [samples] holds one entry per second of the sliding window, oldest first, in
 * bytes per second; [scale] is the top of the graph and is the window's own
 * peak, so the curve fills the box the way a monitor should.
 */
data class LivePulse(
    val windowSeconds: Int = PulseEngine.DEFAULT_WINDOW_SECONDS,
    val samples: List<Double> = emptyList(),
    val scale: Double = 0.0,
    val inBps: Double = 0.0,
    val outBps: Double = 0.0,
    val txPerSecond: Double = 0.0,
    val peers: Map<Int, PeerPulse> = emptyMap(),
    val events: List<PulseEvent> = emptyList(),
    /** When the sampler last wrote this snapshot (monotonic millis). */
    val updatedAt: Long = 0L
)

/**
 * Turns successive byte counters into the rates and the curve the card shows.
 *
 * The engine is fed from one place (the sampler in [NodeController]) and is
 * deliberately free of Android: every input is a value, every output is a
 * [LivePulse], so the arithmetic that a user reads as "15.3 KB/s" is unit
 * tested instead of trusted.
 *
 * Rates are smoothed with an exponential moving average so a single poll that
 * arrives a few hundred milliseconds late does not paint a spike; the first
 * measured rate is taken as it is, so the card does not climb out of zero over
 * the first few seconds of a running node. A step longer than
 * [MAX_STEP_MILLIS] is not one second of traffic - the sampler was paused, the
 * app was asleep, or the node was restarted - and is drawn as zeros rather
 * than spread over the window as an invented average.
 */
class PulseEngine(
    private val windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
    private val stepMillis: Long = DEFAULT_STEP_MILLIS,
    private val txWindowMillis: Long = DEFAULT_TX_WINDOW_MILLIS,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val smoothing: Double = DEFAULT_SMOOTHING
) {

    private class PeerTrack(
        var address: String,
        var inBytes: Long = 0L,
        var outBytes: Long = 0L,
        var inBps: Double = 0.0,
        var outBps: Double = 0.0,
        var measured: Boolean = false,
        var lastActivityAt: Long = 0L
    )

    /** One second of traffic each, oldest first. */
    private val samples = ArrayDeque<Double>()

    /** Newest first, as the card draws them. */
    private val events = ArrayDeque<PulseEvent>()

    /** When each accepted transaction was seen, for [LivePulse.txPerSecond]. */
    private val accepted = ArrayDeque<Long>()

    private val peers = LinkedHashMap<Int, PeerTrack>()
    private val lastEventAt = HashMap<Int, Long>()

    private var lastTotalsAt = 0L
    private var lastIn = 0L
    private var lastOut = 0L
    private var emaIn = 0.0
    private var emaOut = 0.0
    private var totalsMeasured = false

    private var lastPeersAt = 0L

    /** Zeroes every figure: a stopped or restarted node starts its curve again. */
    fun reset() {
        samples.clear()
        events.clear()
        accepted.clear()
        peers.clear()
        lastEventAt.clear()
        lastTotalsAt = 0L
        lastIn = 0L
        lastOut = 0L
        emaIn = 0.0
        emaOut = 0.0
        totalsMeasured = false
        lastPeersAt = 0L
    }

    /**
     * Drops the event lines but keeps the curve.
     *
     * The card is a live view: a line it shows is news of the last second, not
     * a log to browse. When the tab leaves the screen the categories that
     * produce those lines are turned off (see [NodeController.setPulseVisible]),
     * and stale lines must not come back looking current on the next visit.
     */
    fun clearEvents() {
        events.clear()
    }

    /**
     * Takes one step: the daemon's new byte totals, its peer list, and the
     * events the log parser read since the previous step.
     *
     * [totals] and [peerList] are null when their RPC call failed; the engine
     * then keeps the rates it has and says nothing new about the traffic,
     * rather than reporting a zero it did not measure.
     */
    fun update(
        atMillis: Long,
        totals: NetTotals? = null,
        peerList: List<PeerInfo>? = null,
        incoming: List<PulseEvent> = emptyList()
    ): LivePulse {
        if (incoming.isNotEmpty()) {
            for (event in incoming) {
                events.addFirst(event)
                if (event.kind == PulseKind.TX && event.direction == PulseDirection.ACCEPTED) {
                    accepted.addLast(atMillis)
                }
                lastEventAt[event.peerId] = atMillis
            }
            while (events.size > maxEvents) events.removeLast()
        }
        while (accepted.isNotEmpty() && atMillis - accepted.first() > txWindowMillis) {
            accepted.removeFirst()
        }
        if (totals != null) sampleTotals(atMillis, totals)
        if (peerList != null) samplePeers(atMillis, peerList)
        return snapshot(atMillis)
    }

    private fun sampleTotals(atMillis: Long, totals: NetTotals) {
        val previousAt = lastTotalsAt
        val previousIn = lastIn
        val previousOut = lastOut
        lastTotalsAt = atMillis
        lastIn = totals.received
        lastOut = totals.sent

        // First sample, or counters that went backwards: the daemon this
        // snapshot describes is not the one the last one did. Nothing was
        // measured across that edge.
        val restarted = previousAt == 0L || totals.received < previousIn || totals.sent < previousOut
        if (restarted) {
            totalsMeasured = false
            emaIn = 0.0
            emaOut = 0.0
            if (samples.isEmpty()) push(0.0)
            return
        }
        val dt = atMillis - previousAt
        if (dt <= 0L) return
        if (dt > MAX_STEP_MILLIS) {
            // The sampler did not run for a while. Those bytes are real, but
            // they were not received at a rate this window can place: draw the
            // gap as idle and start measuring again from here.
            val missed = ((dt + stepMillis - 1) / stepMillis).toInt().coerceIn(1, windowSeconds)
            repeat(missed) { push(0.0) }
            totalsMeasured = false
            emaIn = 0.0
            emaOut = 0.0
            return
        }
        val seconds = dt / 1000.0
        val rawIn = (totals.received - previousIn).coerceAtLeast(0L).toDouble() / seconds
        val rawOut = (totals.sent - previousOut).coerceAtLeast(0L).toDouble() / seconds
        emaIn = if (totalsMeasured) emaIn + smoothing * (rawIn - emaIn) else rawIn
        emaOut = if (totalsMeasured) emaOut + smoothing * (rawOut - emaOut) else rawOut
        totalsMeasured = true
        push(emaIn + emaOut)
    }

    private fun samplePeers(atMillis: Long, peerList: List<PeerInfo>) {
        val dt = if (lastPeersAt == 0L) 0L else atMillis - lastPeersAt
        val usable = dt in 1..MAX_STEP_MILLIS
        val seconds = if (usable) dt / 1000.0 else 0.0
        val present = HashSet<Int>(peerList.size)
        for (peer in peerList) {
            present += peer.nodeId
            val track = peers.getOrPut(peer.nodeId) { PeerTrack(peer.address) }
            track.address = peer.address
            if (usable) {
                val inDelta = peer.bytesReceived - track.inBytes
                val outDelta = peer.bytesSent - track.outBytes
                val rawIn = if (inDelta < 0L) 0.0 else inDelta.toDouble() / seconds
                val rawOut = if (outDelta < 0L) 0.0 else outDelta.toDouble() / seconds
                track.inBps = if (track.measured) track.inBps + smoothing * (rawIn - track.inBps) else rawIn
                track.outBps = if (track.measured) track.outBps + smoothing * (rawOut - track.outBps) else rawOut
                track.measured = true
                if (inDelta > 0L || outDelta > 0L) track.lastActivityAt = atMillis
            }
            track.inBytes = peer.bytesReceived
            track.outBytes = peer.bytesSent
        }
        peers.keys.retainAll(present)
        lastPeersAt = atMillis
    }

    private fun snapshot(atMillis: Long): LivePulse {
        val values = samples.toList()
        val peak = values.maxOrNull() ?: 0.0
        return LivePulse(
            windowSeconds = windowSeconds,
            samples = values,
            scale = max(peak, MIN_SCALE),
            inBps = emaIn,
            outBps = emaOut,
            txPerSecond = if (txWindowMillis <= 0L) 0.0 else {
                accepted.size * 1000.0 / txWindowMillis.toDouble()
            },
            peers = peers.mapValues { (id, track) ->
                PeerPulse(
                    peerId = id,
                    address = track.address,
                    inBps = track.inBps,
                    outBps = track.outBps,
                    lastActivityAt = max(track.lastActivityAt, lastEventAt[id] ?: 0L)
                )
            },
            events = events.toList(),
            updatedAt = atMillis
        )
    }

    private fun push(value: Double) {
        samples.addLast(value)
        while (samples.size > windowSeconds) samples.removeFirst()
    }

    companion object {
        /** The window the sparkline covers. */
        const val DEFAULT_WINDOW_SECONDS = 60

        /** One sample a second; the card is a pulse, not a report. */
        const val DEFAULT_STEP_MILLIS = 1_000L

        /** Accepted transactions are counted per second over this window. */
        const val DEFAULT_TX_WINDOW_MILLIS = 10_000L

        /** How many event lines the engine keeps; the card draws the newest four. */
        const val DEFAULT_MAX_EVENTS = 6

        /** Weight of a new measurement in the moving average. */
        const val DEFAULT_SMOOTHING = 0.45

        /**
         * A step longer than this is a gap, not a rate: the sampler runs once a
         * second, so anything past a few seconds means it was not running.
         */
        const val MAX_STEP_MILLIS = 5_000L

        /** Floor of the graph's vertical scale, so an idle node draws flat at 0. */
        const val MIN_SCALE = 1_024.0
    }
}

/**
 * How one event is written on the card: a two-character mark and the rest of
 * the line.
 *
 * Built here rather than in the composable so the wording is unit tested with
 * the parser that produces the inputs.
 */
object PulseText {

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB")

    /** `OK`, `<-`, `->` or `<>`: what the line is about, at a glance. */
    fun mark(event: PulseEvent): String = when (event.direction) {
        PulseDirection.ACCEPTED -> "OK"
        PulseDirection.IN -> "<-"
        PulseDirection.OUT -> "->"
        PulseDirection.ROUND_TRIP -> "<>"
    }

    /** The line after the mark, e.g. `TX 9a4f..c812 · 248 B · 1.2ms`. */
    fun body(event: PulseEvent): String {
        val verb = when (event.kind) {
            PulseKind.TX -> "TX"
            PulseKind.INV -> "INV"
            PulseKind.GETDATA -> if (event.direction == PulseDirection.OUT) "GET" else "GETDATA"
            PulseKind.PING -> "PING"
            PulseKind.PONG -> "PONG"
            PulseKind.BLOCK -> "BLOCK"
        }
        val parts = mutableListOf<String>()
        when (event.kind) {
            PulseKind.TX -> {
                parts += event.hash?.let { middle(it) } ?: host(event)
                event.bytes?.let { parts += bytes(it) }
                event.pingMs?.let { parts += ping(it) }
            }
            PulseKind.BLOCK -> {
                event.hash?.let { parts += middle(it) }
                event.blockHeight?.let { parts += "h $it" }
            }
            PulseKind.INV -> {
                parts += host(event)
                parts += if (event.count == 1) "1 new tx" else "${event.count} new tx"
            }
            PulseKind.GETDATA -> {
                parts += host(event)
                parts += if (event.direction == PulseDirection.OUT) {
                    if (event.count == 1) "1 tx" else "${event.count} tx"
                } else {
                    if (event.count == 1) "1 inv" else "${event.count} inv"
                }
            }
            PulseKind.PONG -> {
                parts += host(event)
                event.pingMs?.let { parts += ping(it) }
            }
            PulseKind.PING -> parts += host(event)
        }
        return if (parts.isEmpty()) verb else "$verb ${parts.joinToString(" \u00b7 ")}"
    }

    /** A peer as the log names it: the address without its port. */
    fun host(event: PulseEvent): String {
        val address = event.peerAddress
        if (address.isBlank()) return "peer ${event.peerId}"
        val host = when {
            address.startsWith("[") -> address.substringBefore(']').removePrefix("[")
            else -> address.substringBeforeLast(':', address)
        }
        return host.ifBlank { address }
    }

    private fun middle(hash: String): String =
        if (hash.length <= 12) hash else "${hash.take(4)}..${hash.takeLast(4)}"

    private fun bytes(value: Long): String {
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return if (unit == 0) "$value B" else String.format(Locale.US, "%.1f %s", amount, BYTE_UNITS[unit])
    }

    private fun ping(ms: Double): String = if (ms < 10.0) {
        String.format(Locale.US, "%.1fms", ms)
    } else {
        String.format(Locale.US, "%.0fms", ms)
    }
}
