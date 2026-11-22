package com.corejbh.pocketcore.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two guards of [NodeController]'s shared work: [RefreshGate] keeps the RPC
 * poll passes from overlapping, and [SnapshotGate] keeps the snapshot operations
 * (import / export / load / clear) from running into each other.
 */
class RefreshGateTest {

    @Test
    fun anOverlappingPassIsDroppedRatherThanQueued() {
        val gate = RefreshGate()
        assertTrue(gate.tryBegin())
        // The pass already running is the fresher one: a second request while it
        // is in flight asks nothing new and must not start another one.
        assertNull(gate.run { "second pass" })
        assertTrue(gate.inFlight)

        gate.end()
        assertFalse(gate.inFlight)
        assertEquals("second pass", gate.run { "second pass" })
    }

    @Test
    fun aPassThatThrowsStillReleasesTheGate() {
        val gate = RefreshGate()
        val outcome = runCatching {
            gate.run { throw IllegalStateException("the RPC client went away") }
        }
        assertTrue(outcome.isFailure)
        assertFalse(gate.inFlight)
        // Without the finally, one failed pass would block every later refresh
        // for the life of the process.
        assertEquals("next pass", gate.run { "next pass" })
    }

    @Test
    fun passesNeverOverlap() = runBlocking {
        val gate = RefreshGate()
        val accepted = AtomicInteger()
        val peak = AtomicInteger()
        val inside = AtomicInteger()
        withTimeout(5_000) {
            coroutineScope {
                repeat(50) {
                    launch(Dispatchers.Default) {
                        gate.run {
                            accepted.incrementAndGet()
                            peak.set(maxOf(peak.get(), inside.incrementAndGet()))
                            delay(1)
                            inside.decrementAndGet()
                        }
                    }
                }
            }
        }
        assertEquals(1, peak.get())
        assertTrue(accepted.get() in 1..50)
    }
}

class SnapshotGateTest {

    @Test
    fun operationsRunOneAtATimeInTheOrderTheyWereAsked() = runBlocking {
        val gate = SnapshotGate()
        val order = Collections.synchronizedList(mutableListOf<String>())
        withTimeout(5_000) {
            coroutineScope {
                launch(Dispatchers.Default) {
                    gate.run {
                        order += "import"
                        delay(200)
                        order += "import-end"
                    }
                }
                // Wait until the import actually holds the gate, so the clear
                // below is a real overlap and not a coincidence of ordering.
                while (!gate.busy) delay(5)
                launch(Dispatchers.Default) {
                    gate.run { order += "clear" }
                }
            }
        }
        // The clear waited for the copy: an import that finishes after a clear
        // would publish a path to a file the clear already deleted.
        assertEquals(listOf("import", "import-end", "clear"), order)
        assertFalse(gate.busy)
    }

    @Test
    fun theGateIsReleasedWhenAnOperationThrows() = runBlocking {
        val gate = SnapshotGate()
        val outcome = runCatching {
            gate.run<Unit> { throw IllegalStateException("the snapshot was rejected") }
        }
        assertTrue(outcome.isFailure)
        assertFalse(gate.busy)
        assertEquals("load", gate.run { "load" })
    }

    @Test
    fun aSnapshotOperationTakesTheStateLockSecondWithoutDeadlock() = runBlocking {
        // The lock order is snapshot gate, then the node state mutex: a start or
        // a stop holds the state mutex and never asks for the gate, so a
        // snapshot operation waiting for the state lock cannot close a cycle.
        val gate = SnapshotGate()
        val state = Mutex()
        withTimeout(5_000) {
            coroutineScope {
                val startOrStop = launch(Dispatchers.Default) {
                    state.withLock { delay(100) }
                }
                val snapshot = launch(Dispatchers.Default) {
                    gate.run { state.withLock { delay(10) } }
                }
                startOrStop.join()
                snapshot.join()
            }
        }
        assertFalse(gate.busy)
    }

    /**
     * The eighth review: a wipe deletes the data directory a LOAD reads its
     * snapshot from and an EXPORT dumps into, so it is one of the gated
     * operations now. It takes the gate first and the state mutex second, in the
     * same order as the other four.
     */
    @Test
    fun aWipeWaitsForAnOperationInFlightAndBlocksTheNextOne() = runBlocking {
        val gate = SnapshotGate()
        val state = Mutex()
        val order = Collections.synchronizedList(mutableListOf<String>())
        withTimeout(5_000) {
            coroutineScope {
                launch(Dispatchers.Default) {
                    gate.run {
                        order += "load-start"
                        delay(200)
                        order += "load-end"
                    }
                }
                while (!gate.busy) delay(5)
                launch(Dispatchers.Default) {
                    // wipeNodeData's shape: gate first, state mutex second.
                    gate.run { state.withLock { order += "wipe" } }
                }
                delay(50)
                launch(Dispatchers.Default) {
                    gate.run { state.withLock { order += "export" } }
                }
            }
        }
        // The wipe could not delete the chainstate under the load, and the export
        // that came after it could not dump the directory the wipe was clearing.
        assertEquals(listOf("load-start", "load-end", "wipe", "export"), order)
        assertFalse(gate.busy)
    }

    @Test
    fun aWipeDoesNotDeadlockAgainstTheStateLock() = runBlocking {
        // A STOP holds the state mutex while the wipe waits for the gate: the wipe
        // must not take the gate and then find the mutex held by something that
        // waits for the gate.
        val gate = SnapshotGate()
        val state = Mutex()
        withTimeout(5_000) {
            coroutineScope {
                val stop = launch(Dispatchers.Default) { state.withLock { delay(100) } }
                val wipe = launch(Dispatchers.Default) { gate.run { state.withLock { delay(10) } } }
                stop.join()
                wipe.join()
            }
        }
        assertFalse(gate.busy)
    }
}
