package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The port checks that keep `InitHTTPServer()` from failing: the daemon is fatal
 * on a busy RPC port, so the app verifies the port before it starts the process.
 */
class PortProbeTest {

    @Test
    fun detectsBusyAndFreeLoopbackPorts() {
        ServerSocket().use { listener ->
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            val busy = listener.localPort
            assertFalse("a listening port must not look free", PortProbe.isFree(busy))
            assertTrue("a listener accepts connections", PortProbe.isListening(busy))
            val alternative = PortProbe.findFree(busy, attempts = 40)
            assertNotNull("a free port must be found past a busy one", alternative)
            assertTrue("fallback port must be free", PortProbe.isFree(alternative!!))
            assertTrue("fallback port must differ", alternative != busy)
        }
    }

    @Test
    fun prefersTheRequestedPortWhenItIsFree() {
        val base = 45_000 + (System.nanoTime() % 500).toInt()
        if (!PortProbe.isFree(base)) return
        assertEquals(base, PortProbe.findFree(base))
    }

    @Test
    fun skipsPortsThatMustNotBeUsed() {
        ServerSocket().use { listener ->
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            val busy = listener.localPort
            val chosen = PortProbe.findFree(busy, avoid = setOf(busy))
            assertNotNull(chosen)
            assertTrue(chosen != busy)
            assertTrue(PortProbe.isFree(chosen!!))
        }
    }

    @Test
    fun refusesReservedRange() {
        assertFalse(PortProbe.isFree(80))
        assertFalse(PortProbe.isFree(0))
    }

    /**
     * A listener on every address holds the port just as a loopback one does,
     * and bitcoind could not bind either: the probe claims the wildcard address
     * too (versionCode 20).
     */
    @Test
    fun aWildcardListenerIsNotAFreePort() {
        ServerSocket().use { listener ->
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 1)
            val busy = listener.localPort
            assertFalse("a wildcard listener holds the port", PortProbe.isFree(busy))
            assertNull("the wildcard address cannot be claimed either", PortProbe.reserveWildcard(busy))
        }
    }

    @Test
    fun aWildcardReservationHoldsThePortUntilItIsClosed() {
        val base = 47_000 + (System.nanoTime() % 400).toInt()
        val reservation = PortProbe.reserveWildcard(base) ?: return
        try {
            assertFalse("a held wildcard port must not look free", PortProbe.isFree(base))
        } finally {
            reservation.close()
        }
        assertTrue("the port must be free again after the hold is released", PortProbe.isFree(base))
    }

    /**
     * When nothing in the window is free the search says so (null) instead of
     * quietly returning the busy port it started from (versionCode 20).
     */
    @Test
    fun findFreeReturnsNullWhenTheWholeWindowIsBusy() {
        assertNull(PortProbe.findFree(50_000, attempts = 5) { false })
        assertNull(PortProbe.findFreeListenPair(50_000, attempts = 5) { false })
    }

    @Test
    fun findFreeSkipsTheAvoidedPorts() {
        val chosen = PortProbe.findFree(50_100, avoid = setOf(50_100, 50_101), attempts = 10) { port ->
            port == 50_102
        }
        assertEquals(50_102, chosen)
    }

    /**
     * The hold that keeps a verified port from being taken while the node starts.
     * It has to block a second claim while it is open and give the port back when
     * it is closed - a leaked reservation would keep the daemon from ever binding.
     */
    @Test
    fun aReservationHoldsThePortUntilItIsClosed() {
        val base = 46_000 + (System.nanoTime() % 400).toInt()
        val reservation = PortProbe.reserve(base) ?: return
        try {
            assertFalse("a held port must not look free", PortProbe.isFree(base))
            assertNull("the same port must not be reservable twice", PortProbe.reserve(base))
        } finally {
            reservation.close()
        }
        assertTrue("the port must be free again after the hold is released", PortProbe.isFree(base))
    }
}
