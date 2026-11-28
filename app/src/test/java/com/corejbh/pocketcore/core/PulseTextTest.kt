package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a real event is written on the card: the mark, then the line. */
class PulseTextTest {

    @Test
    fun anAcceptedTransactionShowsItsIdSizeAndThePeersRoundTrip() {
        val event = PulseEvent(
            kind = PulseKind.TX,
            direction = PulseDirection.ACCEPTED,
            peerId = 3,
            peerAddress = "190.136.162.61:8333",
            bytes = 248,
            hash = "9a4f1d0c8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a39281706f5e4d3c2b1a0f9c812",
            pingMs = 1.24,
            atMillis = 1
        )
        assertEquals("OK", PulseText.mark(event))
        assertEquals("TX 9a4f..c812 \u00b7 248 B \u00b7 1.2ms", PulseText.body(event))
    }

    @Test
    fun anAnnouncementIsShownAsThePeerWithoutItsPort() {
        val event = PulseEvent(
            kind = PulseKind.INV,
            direction = PulseDirection.IN,
            peerId = 2,
            peerAddress = "142.113.218.28:8333",
            count = 12,
            atMillis = 1
        )
        assertEquals("<-", PulseText.mark(event))
        assertEquals("INV 142.113.218.28 \u00b7 12 new tx", PulseText.body(event))
    }

    @Test
    fun oneAnnouncementIsNotWrittenAsTwelve() {
        val event = PulseEvent(
            kind = PulseKind.INV,
            direction = PulseDirection.IN,
            peerId = 2,
            peerAddress = "142.113.218.28:8333",
            count = 1,
            atMillis = 1
        )
        assertEquals("INV 142.113.218.28 \u00b7 1 new tx", PulseText.body(event))
    }

    @Test
    fun ourRequestForDataIsAnOutgoingGet() {
        val event = PulseEvent(
            kind = PulseKind.GETDATA,
            direction = PulseDirection.OUT,
            peerId = 0,
            peerAddress = "190.136.162.61:8333",
            atMillis = 1
        )
        assertEquals("->", PulseText.mark(event))
        assertEquals("GET 190.136.162.61 \u00b7 1 tx", PulseText.body(event))
    }

    @Test
    fun aPeersRequestForDataIsIncoming() {
        val event = PulseEvent(
            kind = PulseKind.GETDATA,
            direction = PulseDirection.IN,
            peerId = 0,
            peerAddress = "190.136.162.61:8333",
            count = 3,
            atMillis = 1
        )
        assertEquals("GETDATA 190.136.162.61 \u00b7 3 inv", PulseText.body(event))
    }

    @Test
    fun apongCarriesTheRoundTripTheDaemonMeasured() {
        val event = PulseEvent(
            kind = PulseKind.PONG,
            direction = PulseDirection.ROUND_TRIP,
            peerId = 5,
            peerAddress = "[2a01:4f8:1c1c:1234::1]:8333",
            pingMs = 342.0,
            atMillis = 1
        )
        assertEquals("<>", PulseText.mark(event))
        assertEquals("PONG 2a01:4f8:1c1c:1234::1 \u00b7 342ms", PulseText.body(event))
    }

    @Test
    fun anAcceptedBlockShowsItsHashAndHeight() {
        val event = PulseEvent(
            kind = PulseKind.BLOCK,
            direction = PulseDirection.ACCEPTED,
            peerId = -1,
            hash = "5935fe9e860b5ba2d73dd27c3e92914b3954a37227c22951ef68275a267ce489",
            blockHeight = 977_949,
            atMillis = 1
        )
        assertEquals("OK", PulseText.mark(event))
        assertEquals("BLOCK 5935..e489 \u00b7 h 977949", PulseText.body(event))
    }

    @Test
    fun aPeerWithoutAnAddressStillNamesItself() {
        val event = PulseEvent(
            kind = PulseKind.PING,
            direction = PulseDirection.OUT,
            peerId = 7,
            atMillis = 1
        )
        assertEquals("PING peer 7", PulseText.body(event))
    }

    @Test
    fun aLargerMessageIsShownInItsOwnUnit() {
        val event = PulseEvent(
            kind = PulseKind.TX,
            direction = PulseDirection.ACCEPTED,
            peerId = 0,
            bytes = 1_048_576,
            hash = "aa",
            atMillis = 1
        )
        assertEquals("TX aa \u00b7 1.0 MB", PulseText.body(event))
    }
}
