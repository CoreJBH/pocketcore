package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the pulse card: byte counters become rates, the window
 * becomes the curve, and the events become the log. Checked with values, so a
 * node's "15.3 KB/s" is the number this class computed and not a slider.
 */
class PulseEngineTest {

    private val engine = PulseEngine()

    @Test
    fun theFirstMeasurementIsTakenAsItIs() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        val pulse = engine.update(atMillis = 2_000L, totals = NetTotals(15_000, 4_600, 0))
        assertEquals(15_000.0, pulse.inBps, 0.001)
        assertEquals(4_600.0, pulse.outBps, 0.001)
        // The curve carries what the card's numbers say: the two directions
        // added, because one graph cannot show two scales.
        assertEquals(19_600.0, pulse.samples.last(), 0.001)
    }

    @Test
    fun laterStepsAreSmoothedWithoutChangingTheOrderOfMagnitude() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        engine.update(atMillis = 2_000L, totals = NetTotals(10_000, 0, 0))
        val pulse = engine.update(atMillis = 3_000L, totals = NetTotals(20_000, 0, 0))
        assertEquals(10_000.0, pulse.inBps, 0.001)
        assertTrue(pulse.samples.last() > 9_000.0 && pulse.samples.last() < 11_000.0)
    }

    @Test
    fun aDaemonRestartDoesNotBecomeANegativeRate() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        engine.update(atMillis = 2_000L, totals = NetTotals(50_000, 20_000, 0))
        // The counters of a daemon that was just started: smaller than before.
        val pulse = engine.update(atMillis = 3_000L, totals = NetTotals(12, 8, 0))
        assertEquals(0.0, pulse.inBps, 0.001)
        assertEquals(0.0, pulse.outBps, 0.001)
        assertTrue(pulse.samples.all { it >= 0.0 })
    }

    @Test
    fun aGapIsDrawnAsIdleRatherThanSpreadOverTheWindow() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        engine.update(atMillis = 2_000L, totals = NetTotals(10_000, 0, 0))
        val before = engine.update(atMillis = 3_000L, totals = NetTotals(20_000, 0, 0))
        val pulse = engine.update(atMillis = 13_000L, totals = NetTotals(60_000, 0, 0))
        assertEquals(0.0, pulse.inBps, 0.001)
        // Ten missing seconds are ten zero samples, not one 4 KB/s sample.
        assertEquals(0.0, pulse.samples.last(), 0.001)
        assertTrue(pulse.samples.size > before.samples.size)
    }

    @Test
    fun theWindowKeepsOnlyItsOwnSeconds() {
        var totals = 0L
        for (step in 0..120) {
            val atMillis = 1_000L + step * 1_000L
            engine.update(atMillis = atMillis, totals = NetTotals(totals, 0, 0))
            totals += 1_000
        }
        val pulse = engine.update(atMillis = 200_000L, totals = NetTotals(totals, 0, 0))
        assertEquals(PulseEngine.DEFAULT_WINDOW_SECONDS, pulse.samples.size)
    }

    @Test
    fun peerRatesComeFromThatPeersOwnCounters() {
        engine.update(atMillis = 1_000L, peerList = listOf(peer(0, 0, 0), peer(3, 0, 0)))
        val pulse = engine.update(
            atMillis = 2_000L,
            peerList = listOf(peer(0, 4_000, 1_000), peer(3, 0, 500))
        )
        assertEquals(4_000.0, pulse.peers.getValue(0).inBps, 0.001)
        assertEquals(1_000.0, pulse.peers.getValue(0).outBps, 0.001)
        assertEquals(0.0, pulse.peers.getValue(3).inBps, 0.001)
        assertEquals(500.0, pulse.peers.getValue(3).outBps, 0.001)
    }

    @Test
    fun aPeerThatMovedBytesIsLitEvenWithoutAParsedEvent() {
        engine.update(atMillis = 1_000L, peerList = listOf(peer(0, 100, 100)))
        val pulse = engine.update(atMillis = 2_000L, peerList = listOf(peer(0, 4_000, 100)))
        assertEquals(2_000L, pulse.peers.getValue(0).lastActivityAt)
    }

    @Test
    fun aPeerThatIsGoneLeavesTheList() {
        engine.update(atMillis = 1_000L, peerList = listOf(peer(0, 0, 0), peer(3, 0, 0)))
        val pulse = engine.update(atMillis = 2_000L, peerList = listOf(peer(3, 10, 10)))
        assertTrue(pulse.peers.containsKey(3))
        assertTrue(!pulse.peers.containsKey(0))
    }

    @Test
    fun anEventLightsItsPeerAndLandsAtTheTopOfTheLog() {
        engine.update(atMillis = 1_000L, peerList = listOf(peer(0, 0, 0)))
        val event = PulseEvent(
            kind = PulseKind.PONG,
            direction = PulseDirection.ROUND_TRIP,
            peerId = 0,
            bytes = 8,
            atMillis = 2_000L
        )
        val pulse = engine.update(atMillis = 2_000L, peerList = listOf(peer(0, 0, 0)), incoming = listOf(event))
        assertEquals(event, pulse.events.first())
        assertEquals(2_000L, pulse.peers.getValue(0).lastActivityAt)
    }

    @Test
    fun theLogKeepsTheNewestLinesAndTheCardDrawsFour() {
        val events = (1..10).map { index ->
            PulseEvent(PulseKind.INV, PulseDirection.IN, peerId = 0, count = index, atMillis = index.toLong())
        }
        val pulse = engine.update(atMillis = 10_000L, incoming = events)
        assertEquals(PulseEngine.DEFAULT_MAX_EVENTS, pulse.events.size)
        // Newest first: the last line the daemon wrote is the first one shown.
        assertEquals(10, pulse.events.first().count)
        assertEquals(5, pulse.events.last().count)
    }

    @Test
    fun theTransactionRateCountsOnlyTheLastWindow() {
        val accepted = (0 until 3).map {
            PulseEvent(PulseKind.TX, PulseDirection.ACCEPTED, peerId = 0, hash = "aa", atMillis = 1_000L)
        }
        var pulse = engine.update(atMillis = 1_000L, incoming = accepted)
        assertEquals(0.3, pulse.txPerSecond, 0.0001)
        pulse = engine.update(atMillis = 20_000L, totals = NetTotals(0, 0, 0))
        assertEquals(0.0, pulse.txPerSecond, 0.0001)
    }

    @Test
    fun aMissingRpcAnswerKeepsTheLastMeasurement() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        val measured = engine.update(atMillis = 2_000L, totals = NetTotals(10_000, 0, 0))
        val pulse = engine.update(atMillis = 3_000L, totals = null, peerList = null)
        assertEquals(measured.inBps, pulse.inBps, 0.001)
        assertEquals(measured.samples.size, pulse.samples.size)
    }

    @Test
    fun theScaleIsThePeakOfTheWindowAndNeverZero() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        engine.update(atMillis = 2_000L, totals = NetTotals(100_000, 0, 0))
        val busy = engine.update(atMillis = 3_000L, totals = NetTotals(200_000, 0, 0))
        assertEquals(busy.samples.maxOrNull() ?: 0.0, busy.scale, 0.001)

        val idle = PulseEngine()
        val quiet = idle.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        assertEquals(PulseEngine.MIN_SCALE, quiet.scale, 0.001)
    }

    @Test
    fun resetEmptiesEverythingForTheNextDaemon() {
        engine.update(atMillis = 1_000L, totals = NetTotals(0, 0, 0))
        engine.update(atMillis = 2_000L, totals = NetTotals(10_000, 5_000, 0), peerList = listOf(peer(0, 10, 10)))
        engine.reset()
        val pulse = engine.update(atMillis = 3_000L, totals = NetTotals(99, 99, 0))
        assertEquals(0.0, pulse.inBps, 0.001)
        assertTrue(pulse.peers.isEmpty())
        assertTrue(pulse.events.isEmpty())
        assertEquals(1, pulse.samples.size)
    }

    @Test
    fun theLogCanBeClearedWithoutLosingTheCurve() {
        val event = PulseEvent(PulseKind.PONG, PulseDirection.ROUND_TRIP, peerId = 0, atMillis = 2_000L)
        val before = engine.update(
            atMillis = 2_000L,
            totals = NetTotals(10_000, 0, 0),
            peerList = listOf(peer(0, 10, 10)),
            incoming = listOf(event)
        )
        assertTrue(before.events.isNotEmpty())
        engine.clearEvents()
        val after = engine.update(atMillis = 3_000L, totals = NetTotals(20_000, 0, 0))
        assertTrue(after.events.isEmpty())
        // What is on screen as rates is still what was measured (the first
        // sample only seeds the counters, so the rate starts with the second).
        assertEquals(10_000.0, after.inBps, 0.001)
        assertEquals(before.samples.size + 1, after.samples.size)
    }

    private fun peer(id: Int, received: Long, sent: Long) = PeerInfo(
        nodeId = id,
        address = "10.0.0.$id:8333",
        userAgent = "/Satoshi:32.1.0/",
        inbound = false,
        pingMs = 1.0,
        bytesReceived = received,
        bytesSent = sent,
        connectionSeconds = 60,
        services = "NETWORK",
        version = 70_017,
        banned = false,
        permissions = ""
    )
}
