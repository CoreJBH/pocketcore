package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counter that keeps a poll reply from resurrecting a stopped node.
 *
 * The race the review describes: STOP is pressed while a `refresh()` is waiting
 * on an RPC answer; the answer arrives and writes `RUNNING` over the `STOPPED`
 * the user just asked for. A reply may only land in the generation it was taken
 * in, and every start and every stop takes a new one.
 */
class LifecycleGenerationTest {

    @Test
    fun aTakenGenerationIsCurrentUntilTheNextEdge() {
        val lifecycle = LifecycleGeneration()
        val taken = lifecycle.next()
        assertTrue(lifecycle.isCurrent(taken))
        assertEquals(taken, lifecycle.current())
    }

    @Test
    fun aStopMakesAnInFlightReplyStale() {
        val lifecycle = LifecycleGeneration()
        val started = lifecycle.next()
        // The poll pass begins...
        assertTrue(lifecycle.isCurrent(started))
        // ...the user presses STOP while its request is on the wire...
        val stopped = lifecycle.next()
        // ...and the reply that comes back now describes the old node.
        assertFalse(lifecycle.isCurrent(started))
        assertTrue(lifecycle.isCurrent(stopped))
    }

    @Test
    fun aStopFollowedByAQuickStartKeepsTheOldReplyStale() {
        // Two edges, not one flip: a stop and the start that follows it cannot
        // make the earlier reply look current again.
        val lifecycle = LifecycleGeneration()
        val first = lifecycle.next()
        lifecycle.next() // stop
        lifecycle.next() // start again
        assertFalse(lifecycle.isCurrent(first))
    }

    @Test
    fun everyEdgeGetsItsOwnNumber() {
        // Monotonic and distinct, so a later start can never hand out a number
        // an earlier reply is still holding.
        val lifecycle = LifecycleGeneration()
        val seen = HashSet<Long>()
        repeat(1_000) { seen.add(lifecycle.next()) }
        assertEquals(1_000, seen.size)
        assertEquals(1_000L, lifecycle.current())
    }
}
