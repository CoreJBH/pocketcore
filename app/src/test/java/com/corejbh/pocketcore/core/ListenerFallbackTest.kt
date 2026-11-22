package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener owner when /proc cannot answer: the Android 10+ SELinux case
 * that left a started node in STARTING forever, and the three facts the
 * fallback accepts instead - daemon alive, port answering, this start's
 * debug.log showing the bind.
 */
class ListenerFallbackTest {

    // Core's own sentences, as v25 through v32 write them to debug.log.
    private val bound = "2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332"
    private val boundV32 = "2026-11-22T05:12:09Z [http] Binding RPC on address 127.0.0.1 port 8332"
    private val bindFailed = "2026-11-22T05:12:09Z [http] Binding RPC on address 127.0.0.1 port 8332 " +
        "failed: Unable to bind to 127.0.0.1:8332 on this computer. Bitcoin Core is probably already running."

    @Test
    fun aDeadDaemonStaysUnknown() {
        // Nothing alive to vouch for: the caller's unexpected-exit handling is
        // the better answer than any verdict a log could give.
        assertEquals(SocketOwner.UNKNOWN, ListenerFallback.owner(alive = false, portOpen = true, bindConfirmed = true))
        assertEquals(
            SocketOwner.UNKNOWN,
            ListenerFallback.owner(alive = false, portOpen = false, bindConfirmed = true)
        )
    }

    @Test
    fun anAliveDaemonWithAConfirmedBindIsOurs() {
        assertEquals(SocketOwner.OURS, ListenerFallback.owner(alive = true, portOpen = true, bindConfirmed = true))
    }

    @Test
    fun aPortThatAnswersWithoutEvidenceStaysForeign() {
        // Something answers on the port, but this start's log never showed our
        // daemon binding it: the same refusal the /proc check would give.
        assertEquals(SocketOwner.FOREIGN, ListenerFallback.owner(alive = true, portOpen = true, bindConfirmed = false))
    }

    @Test
    fun anAliveDaemonWithASilentPortIsNotListening() {
        // Core opens its RPC server late in startup; this is the warm-up state
        // the poll loop already knows how to wait through.
        assertEquals(
            SocketOwner.NOT_LISTENING,
            ListenerFallback.owner(alive = true, portOpen = false, bindConfirmed = false)
        )
        assertEquals(
            SocketOwner.NOT_LISTENING,
            ListenerFallback.owner(alive = true, portOpen = false, bindConfirmed = true)
        )
    }

    @Test
    fun recognizesCoresBindLine() {
        assertTrue(ListenerFallback.rpcBindConfirmed(bound, 8332))
        assertTrue(ListenerFallback.rpcBindConfirmed(boundV32, 8332))
    }

    @Test
    fun theFailedBindIsNotEvidence() {
        assertFalse(ListenerFallback.rpcBindConfirmed(bindFailed, 8332))
        // A failure and a success in one window: the successful line still
        // counts (a retried start after a first collision).
        assertTrue(ListenerFallback.rpcBindConfirmed("$bindFailed\n$bound", 8332))
    }

    @Test
    fun anotherPortIsNotEvidence() {
        assertFalse(ListenerFallback.rpcBindConfirmed(bound, 8333))
        assertFalse(ListenerFallback.rpcBindConfirmed("Binding RPC on address 127.0.0.1 port 48332", 8332))
    }

    @Test
    fun aNonLoopbackBindIsNotEvidence() {
        assertFalse(ListenerFallback.rpcBindConfirmed("Binding RPC on address 0.0.0.0 port 8332", 8332))
        assertFalse(ListenerFallback.rpcBindConfirmed("Binding RPC on address 192.168.1.7 port 8332", 8332))
        assertTrue(ListenerFallback.rpcBindConfirmed("Binding RPC on address ::1 port 8332", 8332))
    }

    @Test
    fun anEmptyOrUnrelatedWindowIsNotEvidence() {
        assertFalse(ListenerFallback.rpcBindConfirmed("", 8332))
        assertFalse(
            ListenerFallback.rpcBindConfirmed(
                "2026-11-22T05:12:00Z Bitcoin Core version v32.1.0\n" +
                    "2026-11-22T05:12:01Z Opening the block database\n",
                8332
            )
        )
    }
}
