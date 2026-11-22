package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ABORT of a watch-only scan.
 *
 * A scan that was running when the node went away left the card busy with a
 * progress bar and an ABORT that did nothing, because the controller returned
 * early when it could not reach the node. The state is now reset either way.
 */
class DescriptorAbortTest {

    @Test
    fun abortClearsARunningScan() {
        val running = DescriptorState(input = "wpkh(xpub...)", busy = true, progress = 0.42)
        val after = abortedScanState(running)
        assertFalse(after.busy)
        assertEquals(0.0, after.progress, 0.0)
        assertEquals("Scan cancelled.", after.error)
        assertEquals("wpkh(xpub...)", after.input)
    }

    @Test
    fun abortWithoutANodeStillResetsTheCard() {
        // The node is stopped, so there is no client to ask; the state was left
        // by a scan the daemon had already ended.
        val stuck = DescriptorState(busy = true, progress = 0.99)
        val after = abortedScanState(stuck)
        assertFalse(after.busy)
        assertEquals(0.0, after.progress, 0.0)
    }

    @Test
    fun abortOnAnIdleCardChangesNothingItShouldNot() {
        val idle = DescriptorState(input = "wpkh(xpub...)", error = "The node did not complete the scan.")
        val after = abortedScanState(idle)
        assertFalse(after.busy)
        assertNull(after.note)
        assertEquals("The node did not complete the scan.", after.error)
    }
}
