package com.corejbh.pocketcore.core

import java.util.concurrent.atomic.AtomicLong

/**
 * A monotonic counter of the daemon's lifecycle edges.
 *
 * Every start and every stop takes the next number; a poll pass that is still
 * in flight keeps the number it began with and asks [isCurrent] before it
 * writes anything back. A reply taken at generation N is a fact about the
 * daemon of generation N, so once a stop or a new start has moved the counter,
 * the reply is dropped instead of being applied to a node the user has since
 * stopped (which is how a STOPPED node could otherwise flip back to RUNNING).
 *
 * A counter rather than a boolean: a stop followed quickly by a start must not
 * make an older reply look current again, and an [AtomicLong] hands every edge
 * a distinct number even when the start, the stop and the poll run on different
 * threads.
 */
internal class LifecycleGeneration {

    private val counter = AtomicLong(0L)

    /** Marks a start or a stop and returns the new generation. */
    fun next(): Long = counter.incrementAndGet()

    /** The newest generation. */
    fun current(): Long = counter.get()

    /** True while no start or stop has happened since [generation]. */
    fun isCurrent(generation: Long): Boolean = counter.get() == generation
}
