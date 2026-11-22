package com.corejbh.pocketcore.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets one [NodeController.refresh] pass run at a time.
 *
 * A refresh is asked for from three places that do not wait for each other: the
 * poll loop (every 0.9-2.2 s), [NodeController.refreshOnceNow] after a peer or
 * snapshot action, and a second action while the first one's answer is still on
 * its way. Two passes in flight read the same `startedAt` and the same state and
 * then write it back in whatever order their RPC replies arrive, so the older
 * reply can land after the newer one: the status line walks backwards, the
 * header-sync window is read twice from the same throttle slot, and a stop that
 * happened between the two is described by a reply about the daemon before it.
 * The pass that is already running is the fresher one - it is asking the same
 * node a moment later - so an overlapping request is dropped rather than
 * queued: a queued pass would only ask again what the running one is about to
 * answer.
 *
 * [run] releases the gate in a `finally`, so a pass that throws (or is
 * cancelled after the body started) cannot leave refreshes blocked for the rest
 * of the process's life.
 */
internal class RefreshGate {

    private val running = AtomicBoolean(false)

    /** True while a pass holds the gate. */
    val inFlight: Boolean get() = running.get()

    /** Runs [block] unless a pass is already running; null in that case. */
    inline fun <T> run(block: () -> T): T? {
        if (!tryBegin()) return null
        try {
            return block()
        } finally {
            end()
        }
    }

    /** Takes the gate, or reports that a pass already holds it. */
    fun tryBegin(): Boolean = running.compareAndSet(false, true)

    /** Gives the gate back. */
    fun end() {
        running.set(false)
    }
}

/**
 * Serializes the snapshot operations: import, export, load and clear.
 *
 * All four work on the same two things - the snapshot file the settings point
 * at, and the one `loadtxoutset` / `dumptxoutset` the daemon can run - and two
 * of them together are a race the user sees: an import that is still copying
 * publishes its path after a CLEAR already removed the file, LOAD reads a file
 * that CLEAR is deleting, or EXPORT starts a dump while LOAD is activating a
 * snapshot and Core is busy walking the chainstate. The operations therefore
 * run one at a time, in the order they were asked for.
 *
 * The gate is taken for the whole operation, including the minutes a snapshot
 * copy or a UTXO dump takes: that is what makes "one at a time" true, and it is
 * why the gate is separate from [NodeController]'s state mutex - holding the
 * node mutex for a long copy would block STOP and the Wi-Fi / charger policy
 * for as long as the file takes.
 *
 * **Lock order:** a snapshot operation takes this gate first and the node state
 * mutex second, only around the short step that writes [NodeController.state]
 * or the settings. Nothing takes them the other way round (a start or stop holds
 * the state mutex and never asks for this gate), so the two cannot deadlock.
 */
internal class SnapshotGate {

    private val gate = Mutex()

    /** True while an operation holds the gate. */
    val busy: Boolean get() = gate.isLocked

    /** Runs [block] as the only snapshot operation. */
    suspend fun <T> run(block: suspend () -> T): T = gate.withLock { block() }
}
