package com.corejbh.pocketcore.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port selection before a start: which port the node gets, and - the security
 * rule - whether the RPC credentials may be sent to whatever holds one.
 */
class PortResolverTest {

    private val defaultRpc = 8332
    private val defaultP2p = 8333

    /** A fake device where [busy] holds a port and nothing else is taken. */
    private inner class Device(vararg busy: Int) {
        val taken = busy.toMutableSet()
        var stopCalls = ArrayList<Int>()
        var straysKilled = 0
        var stopAnswers = true

        val resolver = PortResolver(
            isFree = { port -> port !in taken },
            findFree = { preferred, avoid ->
                var candidate = preferred
                while (candidate in taken || candidate in avoid) candidate++
                candidate
            },
            findFreeListenPair = { preferred, avoid ->
                var candidate = preferred
                while (candidate in taken || candidate + 1 in taken ||
                    candidate in avoid || candidate + 1 in avoid
                ) {
                    candidate++
                }
                candidate
            },
            awaitFree = { port, _ ->
                // The daemon is gone once the stop request was answered.
                if (stopCalls.isNotEmpty() && stopAnswers) taken.remove(port)
                port !in taken
            }
        )

        fun resolve(ownDaemonPort: Int? = null) = runBlocking {
            resolver.resolve(
                defaultRpc = defaultRpc,
                defaultP2p = defaultP2p,
                ownDaemonRpcPort = ownDaemonPort,
                stopOwnDaemon = { port ->
                    stopCalls.add(port)
                    stopAnswers
                },
                killStrays = { straysKilled++ }
            )
        }
    }

    @Test
    fun keepsTheDefaultPortsWhenNothingHoldsThem() {
        val device = Device()
        val choice = device.resolve()
        assertEquals(defaultRpc, choice.rpc)
        assertEquals(defaultP2p, choice.p2p)
        assertNull(choice.note)
        assertFalse(choice.rotateCredentials)
        assertTrue(device.stopCalls.isEmpty())
    }

    /**
     * Regression: a busy RPC port used to receive `stopQuick()` before anything
     * was known about its owner, which handed the RPC username and password to
     * whatever process held the port.
     */
    @Test
    fun neverSendsCredentialsToAForeignProcess() {
        val device = Device(defaultRpc)
        val choice = device.resolve(ownDaemonPort = null)
        assertTrue("no stop request may be sent without a daemon of ours", device.stopCalls.isEmpty())
        assertEquals(0, device.straysKilled)
        assertTrue(choice.rpc > defaultRpc)
        assertTrue(choice.rpc != choice.p2p && choice.rpc != choice.p2p + 1)
        assertTrue(choice.note!!.contains("in use by another app"))
        assertTrue(choice.note!!.contains("freshly generated RPC password"))
        assertTrue(choice.rotateCredentials)
    }

    @Test
    fun stopsOurOwnDaemonOnThePortOurConfRecords() {
        val device = Device(defaultRpc)
        val choice = device.resolve(ownDaemonPort = defaultRpc)
        assertEquals(listOf(defaultRpc), device.stopCalls)
        assertEquals(defaultRpc, choice.rpc)
        assertNull(choice.note)
        assertFalse(choice.rotateCredentials)
    }

    /**
     * Our daemon can sit on a moved port while another app holds the default one:
     * the stop request goes to our port only, and the default port is left alone.
     */
    @Test
    fun stopsOurDaemonOnItsOwnPortAndLeavesTheForeignPortAlone() {
        val device = Device(defaultRpc)
        val choice = device.resolve(ownDaemonPort = defaultRpc + 4)
        assertEquals(listOf(defaultRpc + 4), device.stopCalls)
        assertFalse("our own daemon is stopped", device.taken.contains(defaultRpc + 4))
        assertTrue(choice.rpc > defaultRpc && choice.rpc != choice.p2p && choice.rpc != choice.p2p + 1)
        assertTrue(choice.rotateCredentials)
        assertEquals(1, device.straysKilled)
    }

    @Test
    fun killsWhatIsLeftWhenTheStopRequestIsNotAnswered() {
        val device = Device(defaultRpc)
        device.stopAnswers = false
        val choice = device.resolve(ownDaemonPort = defaultRpc)
        assertEquals(listOf(defaultRpc), device.stopCalls)
        assertEquals("a stray under our name is always killed", 1, device.straysKilled)
        // The port stayed busy, so the node moves and the pair is replaced.
        assertTrue(choice.rpc > defaultRpc)
        assertTrue(choice.rotateCredentials)
    }

    @Test
    fun movesTheP2pPortPairAndKeepsThemApart() {
        val device = Device(defaultP2p, defaultP2p + 1)
        val choice = device.resolve()
        assertEquals(defaultRpc, choice.rpc)
        assertTrue(choice.p2p > defaultP2p)
        assertTrue(choice.p2p != choice.rpc && choice.p2p + 1 != choice.rpc)
        assertTrue(choice.note!!.contains("P2P port 8333 is in use"))
    }

    @Test
    fun anRpcPortTakenByTheP2pListenerIsMovedWithoutRotating() {
        // A defensive branch: the listen-pair search is given the RPC port as an
        // avoided port, so this only happens if a device ever reports one as
        // free and then hands it to the P2P listener. The RPC port then moves and
        // stays a port of our own node, so the credentials are not replaced.
        val resolver = PortResolver(
            isFree = { port -> port != defaultRpc && port != defaultP2p },
            findFree = { preferred, _ -> preferred + 1 },
            findFreeListenPair = { _, _ -> defaultRpc },
            awaitFree = { port, _ -> port != defaultRpc && port != defaultP2p }
        )
        val choice = runBlocking {
            resolver.resolve(defaultRpc, defaultP2p, null, { true }, {})
        }
        assertEquals(defaultRpc, choice.p2p)
        assertEquals(defaultRpc + 1, choice.rpc)
        assertTrue(choice.note!!.contains("collides with the P2P listener"))
        assertFalse(choice.rotateCredentials)
    }
}
