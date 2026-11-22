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
}
