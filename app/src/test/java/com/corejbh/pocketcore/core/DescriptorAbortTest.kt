package com.corejbh.pocketcore.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 *
 * The order ABORT works in matters (versionCode 20): the scan job is cancelled
 * before the daemon is asked, because scanAbort() makes the running scan return
 * at once - with an error - and a job still alive would publish that error over
 * the cancelled state.
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

    @Test
    fun abortCancelsTheScanBeforeItAsksTheDaemon() = runBlocking {
        val steps = ArrayList<String>()
        abortInOrder(
            cancelScan = { steps.add("cancel") },
            publishCancelled = { steps.add("publish") },
            askDaemon = { steps.add("daemon") }
        )
        assertEquals(listOf("cancel", "publish", "daemon"), steps)
    }

    /** A daemon that cannot be asked still leaves the card cancelled. */
    @Test
    fun aFailedDaemonCallDoesNotUndoTheCancellation() = runBlocking {
        val steps = ArrayList<String>()
        runCatching {
            abortInOrder(
                cancelScan = { steps.add("cancel") },
                publishCancelled = { steps.add("publish") },
                askDaemon = {
                    steps.add("daemon")
                    throw IllegalStateException("the node is gone")
                }
            )
        }
        assertEquals(listOf("cancel", "publish", "daemon"), steps)
    }

    /**
     * A reply to a blocking scan call that arrived after the scan was cancelled
     * is not a reply to publish: the check after the call ends the coroutine
     * instead (versionCode 20).
     */
    @Test
    fun aReplyAfterTheScanWasCancelledIsNotHandedBack() = runBlocking {
        var published = false
        val job = launch {
            answerIfStillWanted {
                // The daemon keeps walking the UTXO set: the call is not
                // interruptible, so the cancel lands while it is in flight.
                delay(200)
                "scan result"
            }
            published = true
        }
        delay(30)
        job.cancel()
        job.join()
        assertFalse("a cancelled scan must publish nothing", published)
        assertFalse(job.isActive)
    }

    @Test
    fun aReplyIsHandedBackWhileTheScanIsStillWanted() = runBlocking {
        assertEquals("scan result", answerIfStillWanted { "scan result" })
    }

    @Test(expected = CancellationException::class)
    fun answerIfStillWantedRethrowsAsACancellation() = runBlocking<Unit> {
        // The exception a cancelled job carries is what the caller rethrows, so
        // the scan coroutine ends as cancelled rather than as a failure.
        answerIfStillWanted<Unit> { throw CancellationException("cancelled") }
    }
}
