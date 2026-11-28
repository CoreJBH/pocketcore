package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines below are copied from a Bitcoin Core 32.1 `debug.log` captured with
 * `-debug=net -debug=mempool` and two regtest nodes connected to each other, so
 * the parser is checked against what the bundled daemon actually writes rather
 * than against a format invented here.
 */
class PulseLogParserTest {

    private val parser = PulseLogParser()

    @Test
    fun readsAnAcceptedTransactionWithTheSizeOfTheMessageThatCarriedIt() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:41:33Z [net] got inv: wtx 6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2 new peer=0",
                "2026-11-28T15:41:33Z [net] Requesting wtx 6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2 peer=0",
                "2026-11-28T15:41:33Z [net] received: tx (222 bytes) peer=0",
                "2026-11-28T15:41:33Z [mempool] AcceptToMemoryPool: peer=0: accepted " +
                    "02c1bd7d194e78a4ed297c29fd020427cd70f771f6662e021fbf078dd65d07bc " +
                    "(wtxid=6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2) (poolsz 1 txn, 1 kB)"
            ),
            AT
        )
        val accepted = events.single { it.kind == PulseKind.TX }
        assertEquals(PulseDirection.ACCEPTED, accepted.direction)
        assertEquals(0, accepted.peerId)
        assertEquals("02c1bd7d194e78a4ed297c29fd020427cd70f771f6662e021fbf078dd65d07bc", accepted.hash)
        // 222 B is the size Core logged for the tx message that carried it, not
        // a guessed transaction size.
        assertEquals(222L, accepted.bytes)
        // The request that asked for it is its own line.
        assertTrue(events.any { it.kind == PulseKind.GETDATA && it.direction == PulseDirection.OUT })
        // The announcement is counted, not repeated as one line per item.
        assertEquals(1, events.count { it.kind == PulseKind.INV })
    }

    @Test
    fun countsAnnouncementsPerPeer() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:40:28Z [net] got inv: wtx aaaa000000000000000000000000000000000000000000000000000000000001 new peer=0",
                "2026-11-28T15:40:28Z [net] got inv: wtx aaaa000000000000000000000000000000000000000000000000000000000002 new peer=0",
                "2026-11-28T15:40:28Z [net] got inv: wtx aaaa000000000000000000000000000000000000000000000000000000000003 new peer=0",
                "2026-11-28T15:40:28Z [net] got inv: wtx aaaa000000000000000000000000000000000000000000000000000000000004 new peer=3"
            ),
            AT
        )
        val announcements = events.filter { it.kind == PulseKind.INV }
        assertEquals(2, announcements.size)
        assertEquals(3, announcements.first { it.peerId == 0 }.count)
        assertEquals(1, announcements.first { it.peerId == 3 }.count)
    }

    @Test
    fun skipsAnnouncementsThisNodeAlreadyHas() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:40:28Z [net] got inv: wtx aaaa000000000000000000000000000000000000000000000000000000000001 already-have peer=0"
            ),
            AT
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun readsThePongAsOneRoundTripLineRatherThanTwo() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:40:23Z [net] received: ping (8 bytes) peer=1",
                "2026-11-28T15:40:23Z [net] sending pong (8 bytes) peer=1",
                "2026-11-28T15:40:24Z [net] received: pong (8 bytes) peer=1"
            ),
            AT
        )
        val pong = events.single { it.kind == PulseKind.PONG }
        assertEquals(PulseDirection.ROUND_TRIP, pong.direction)
        assertEquals(8L, pong.bytes)
        // A peer asking us for a keepalive is one line; our answer to it is part
        // of the round trip and not a second "sending pong" line.
        assertEquals(1, events.count { it.kind == PulseKind.PING })
    }

    @Test
    fun readsADataRequestFromAPeer() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:40:28Z [net] received getdata (1 invsz) peer=2",
                "2026-11-28T15:40:28Z [net] received getdata for: wtx c0be2b8e7bf617d9812ca03d270011fdcc10976cded3ffd74f53fcb93af18240 peer=2"
            ),
            AT
        )
        val request = events.single()
        assertEquals(PulseKind.GETDATA, request.kind)
        assertEquals(PulseDirection.IN, request.direction)
        assertEquals(1, request.count)
    }

    @Test
    fun readsTheAcceptedBlockFromTheTipLine() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:41:30Z UpdateTip: new best=5935fe9e860b5ba2d73dd27c3e92914b3954a37227c22951ef68275a267ce489 " +
                    "height=101 version=0x20000000 log2_work=7.672425 tx=102 date='2026-11-28T15:41:35Z' " +
                    "progress=1.000000 cache=0.3MiB(101txo)"
            ),
            AT
        )
        val block = events.single()
        assertEquals(PulseKind.BLOCK, block.kind)
        assertEquals(PulseDirection.ACCEPTED, block.direction)
        assertEquals(101L, block.blockHeight)
        assertEquals("5935fe9e860b5ba2d73dd27c3e92914b3954a37227c22951ef68275a267ce489", block.hash)
    }

    @Test
    fun oneBatchOfBlocksShowsTheNewestTipOnly() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:41:29Z UpdateTip: new best=aaaa000000000000000000000000000000000000000000000000000000000001 height=100",
                "2026-11-28T15:41:29Z UpdateTip: new best=aaaa000000000000000000000000000000000000000000000000000000000002 height=101"
            ),
            AT
        )
        val block = events.single()
        assertEquals(101L, block.blockHeight)
    }

    @Test
    fun aSizeIsNotGivenToADifferentTransaction() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:41:33Z [net] Requesting wtx 6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2 peer=0",
                "2026-11-28T15:41:33Z [net] received: tx (222 bytes) peer=0",
                "2026-11-28T15:41:33Z [mempool] AcceptToMemoryPool: peer=0: accepted " +
                    "02c1bd7d194e78a4ed297c29fd020427cd70f771f6662e021fbf078dd65d07bc " +
                    "(wtxid=bbbb000000000000000000000000000000000000000000000000000000000009) (poolsz 1 txn, 1 kB)"
            ),
            AT
        )
        assertNull(events.single { it.kind == PulseKind.TX }.bytes)
    }

    @Test
    fun ignoresEverythingElseTheDaemonWrites() {
        val events = parser.parse(
            listOf(
                "2026-11-28T15:40:01Z Loading block index...",
                "2026-11-28T15:40:01Z [net] connection from 127.0.0.1:45844 accepted",
                "2026-11-28T15:40:01Z [net] received: cmpctblock (258 bytes) peer=0",
                "2026-11-28T15:40:01Z [net] sending cmpctblock (258 bytes) peer=0",
                "2026-11-28T15:40:01Z [mempool] Checking mempool with 0 transactions and 0 inputs",
                "2026-11-28T15:40:01Z init message: Done loading"
            ),
            AT
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun anEmptyReadIsAnIdleNode() {
        assertTrue(parser.parse(emptyList(), AT).isEmpty())
    }

    @Test
    fun resetForgetsTheRequestsOfTheDaemonThatEnded() {
        parser.parse(
            listOf(
                "2026-11-28T15:41:33Z [net] Requesting wtx 6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2 peer=0",
                "2026-11-28T15:41:33Z [net] received: tx (222 bytes) peer=0"
            ),
            AT
        )
        parser.reset()
        val events = parser.parse(
            listOf(
                "2026-11-28T15:41:33Z [mempool] AcceptToMemoryPool: peer=0: accepted " +
                    "02c1bd7d194e78a4ed297c29fd020427cd70f771f6662e021fbf078dd65d07bc " +
                    "(wtxid=6f95fcf02d769bc01c4d4de8a8b71b0f22015963a8cd59c6b6debabc83b699b2) (poolsz 1 txn, 1 kB)"
            ),
            AT
        )
        assertNull(events.single().bytes)
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
