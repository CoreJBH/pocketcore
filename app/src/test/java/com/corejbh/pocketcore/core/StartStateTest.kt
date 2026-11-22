package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two state rules of the sixth review.
 *
 *  - A transient RPC failure must not walk a RUNNING node back to STARTING.
 *  - START NOW (`forceOverride`) is a bypass for one start, not for the rest of
 *    the run: it is dropped as soon as the real gate condition is met.
 */
class StartStateTest {

    @Test
    fun aRunningNodeStaysRunningThroughATimeout() {
        assertEquals(
            NodeStatus.RUNNING,
            statusAfterTransientRpcFailure(NodeStatus.RUNNING, withinStartupGrace = true)
        )
        assertEquals(
            NodeStatus.RUNNING,
            statusAfterTransientRpcFailure(NodeStatus.RUNNING, withinStartupGrace = false)
        )
    }

    @Test
    fun aStartingNodeIsWarmingUpWithinTheGrace() {
        assertEquals(
            NodeStatus.STARTING,
            statusAfterTransientRpcFailure(NodeStatus.STARTING, withinStartupGrace = true)
        )
    }

    @Test
    fun pastTheGraceTheStatusIsLeftAlone() {
        // After the startup grace a missing answer only changes the detail line;
        // the status it belonged to stays as it was.
        assertEquals(
            NodeStatus.STARTING,
            statusAfterTransientRpcFailure(NodeStatus.STARTING, withinStartupGrace = false)
        )
        assertEquals(
            NodeStatus.WAITING,
            statusAfterTransientRpcFailure(NodeStatus.WAITING, withinStartupGrace = false)
        )
        assertEquals(
            NodeStatus.ERROR,
            statusAfterTransientRpcFailure(NodeStatus.ERROR, withinStartupGrace = false)
        )
    }

    @Test
    fun theOverrideHoldsWhileTheGateIsClosed() {
        assertTrue(forceOverrideAfterGate(forceOverride = true, gateOpen = false))
    }

    @Test
    fun theOverrideIsDroppedOnceTheGateOpens() {
        assertFalse(forceOverrideAfterGate(forceOverride = true, gateOpen = true))
    }

    @Test
    fun noOverrideStaysNoOverride() {
        assertFalse(forceOverrideAfterGate(forceOverride = false, gateOpen = false))
        assertFalse(forceOverrideAfterGate(forceOverride = false, gateOpen = true))
    }
}
