package com.corejbh.pocketcore.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a sensitive RPC (`loadtxoutset`, `dumptxoutset`, `scantxoutset`) may be
 * sent, read fresh instead of trusting the one-time listener proof
 * (versionCode 21).
 *
 * A node can be verified in one second and gone in the next - the user presses
 * STOP, Android kills the daemon, another app takes the port - and these three
 * requests change or walk the chainstate over minutes.
 */
class SensitiveRpcTest {

    private fun facts(
        status: NodeStatus = NodeStatus.RUNNING,
        listenerVerified: Boolean = true,
        alive: Boolean = true,
        shouldRun: Boolean = true,
        generation: Long = 7L,
        currentGeneration: Long = 7L,
        owner: SocketOwner = SocketOwner.OURS
    ) = SensitiveRpcFacts(
        status = status,
        listenerVerified = listenerVerified,
        alive = alive,
        shouldRun = shouldRun,
        generation = generation,
        currentGeneration = currentGeneration,
        owner = owner
    )

    @Test
    fun aRunningVerifiedLiveNodeMayRunASensitiveRequest() {
        assertTrue(sensitiveRpcAllowed(facts()))
    }

    @Test
    fun theCachedListenerProofAloneIsNotEnough() {
        // The flag says a port was proved ours at some earlier moment; the port
        // itself now belongs to something else.
        assertFalse(sensitiveRpcAllowed(facts(owner = SocketOwner.FOREIGN)))
        assertFalse(sensitiveRpcAllowed(facts(owner = SocketOwner.UNKNOWN)))
        assertFalse(sensitiveRpcAllowed(facts(owner = SocketOwner.NOT_LISTENING)))
    }

    @Test
    fun aStoppedOrRestartedNodeRefuses() {
        assertFalse(sensitiveRpcAllowed(facts(generation = 6L, currentGeneration = 7L)))
        assertFalse(sensitiveRpcAllowed(facts(shouldRun = false)))
    }

    @Test
    fun aDeadProcessOrAnUnverifiedListenerRefuses() {
        assertFalse(sensitiveRpcAllowed(facts(alive = false)))
        assertFalse(sensitiveRpcAllowed(facts(listenerVerified = false)))
        for (status in NodeStatus.entries.filter { it != NodeStatus.RUNNING }) {
            assertFalse("$status must not open a sensitive request", sensitiveRpcAllowed(facts(status = status)))
        }
    }

    @Test
    fun everyRefusalSaysWhatToDo() {
        // The first fact that fails decides the sentence, in the order a reader
        // can act on.
        assertTrue(sensitiveRpcRefusal(facts(alive = false)).contains("process is gone"))
        assertTrue(sensitiveRpcRefusal(facts(status = NodeStatus.STOPPED)).contains("not running"))
        assertTrue(sensitiveRpcRefusal(facts(owner = SocketOwner.FOREIGN)).contains("no longer confirmed"))
        assertTrue(
            sensitiveRpcRefusal(facts(generation = 3L, currentGeneration = 4L))
                .contains("stopped or restarted")
        )
        assertTrue(sensitiveRpcRefusal(facts(shouldRun = false)).contains("stopped or restarted"))
        assertTrue(sensitiveRpcRefusal(facts(listenerVerified = false)).contains("not been verified"))
        // Every combination produces a non-blank sentence.
        assertTrue(sensitiveRpcRefusal(facts()).isNotBlank())
    }
}
