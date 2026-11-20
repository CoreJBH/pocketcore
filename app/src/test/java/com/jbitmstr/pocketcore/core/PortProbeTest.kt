package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertTrue("fallback port must be free", PortProbe.isFree(alternative))
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
            assertTrue(chosen != busy)
            assertTrue(PortProbe.isFree(chosen))
        }
    }

    @Test
    fun refusesReservedRange() {
        assertFalse(PortProbe.isFree(80))
        assertFalse(PortProbe.isFree(0))
    }
}
