package com.corejbh.pocketcore.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a closed gate (Wi-Fi only, charging only) may turn the node WAITING:
 * only when it is running or meant to run. A STOPPED node stays stopped when
 * the policy is re-evaluated by a settings edit.
 */
class GatePolicyTest {

    @Test
    fun aRunningNodeShowsWaitingWhenTheGateCloses() {
        assertTrue(gateMayWait(nodeAlive = true, shouldRun = false))
        assertTrue(gateMayWait(nodeAlive = true, shouldRun = true))
    }

    @Test
    fun aNodeMeantToRunShowsWaiting() {
        assertTrue(gateMayWait(nodeAlive = false, shouldRun = true))
    }

    @Test
    fun aStoppedNodeStaysStopped() {
        // The fifth review's finding: editing the settings while the phone was
        // off Wi-Fi turned a node the user had stopped into WAITING.
        assertFalse(gateMayWait(nodeAlive = false, shouldRun = false))
    }
}
