package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tail reader: it must hand over exactly the lines written since the last
 * call, never the ones from before the node started, and never half a line.
 */
class DebugLogTailTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsOnlyWhatWasWrittenAfterAttaching() {
        val log = folder.newFile("debug.log")
        log.writeText("2026-11-28T15:40:00Z old line\n2026-11-28T15:40:01Z another old line\n")
        val tail = DebugLogTail(log)
        assertTrue(tail.readNew().isEmpty())

        log.appendText("2026-11-28T15:40:28Z [net] received: inv (37 bytes) peer=0\n")
        assertEquals(
            listOf("2026-11-28T15:40:28Z [net] received: inv (37 bytes) peer=0"),
            tail.readNew()
        )
        // Nothing new is nothing, not the same line again.
        assertTrue(tail.readNew().isEmpty())
    }

    @Test
    fun holdsHalfALineUntilTheDaemonFinishesIt() {
        val log = folder.newFile("debug.log")
        val tail = DebugLogTail(log)
        log.appendText("2026-11-28T15:40:28Z [net] received: tx (222 bytes)")
        assertTrue(tail.readNew().isEmpty())
        log.appendText(" peer=0\n")
        assertEquals(
            listOf("2026-11-28T15:40:28Z [net] received: tx (222 bytes) peer=0"),
            tail.readNew()
        )
    }

    @Test
    fun aShrunkLogIsNotReplayedAsIfItWereNews() {
        val log = folder.newFile("debug.log")
        log.writeText("2026-11-28T15:40:00Z before the start\n")
        val tail = DebugLogTail(log)
        log.appendText("2026-11-28T15:40:28Z [net] received: pong (8 bytes) peer=0\n")
        assertEquals(1, tail.readNew().size)

        // Core shrinks debug.log at startup and keeps a tail of the previous run.
        log.writeText("2026-11-27T10:00:00Z [net] received: inv (37 bytes) peer=2\n")
        assertTrue(tail.readNew().isEmpty())

        log.appendText("2026-11-28T15:41:00Z [net] received: pong (8 bytes) peer=1\n")
        assertEquals(
            listOf("2026-11-28T15:41:00Z [net] received: pong (8 bytes) peer=1"),
            tail.readNew()
        )
    }

    @Test
    fun aBacklogTooLargeToShowIsDroppedForItsTail() {
        val log = folder.newFile("debug.log")
        val tail = DebugLogTail(log)
        val filler = "2026-11-28T15:40:28Z [net] received: inv (37 bytes) peer=0\n"
        val many = filler.repeat(20_000)
        log.appendText(many)
        log.appendText("2026-11-28T15:41:00Z [net] received: pong (8 bytes) peer=1\n")
        val lines = tail.readNew()
        // Reading resumes near the end of the file, so the newest line is there
        // and the megabytes of old ones were not parsed.
        assertTrue(lines.size < 20_000)
        assertTrue(lines.last().endsWith("peer=1"))
    }

    @Test
    fun aMissingLogIsAnEmptyRead() {
        val tail = DebugLogTail(File(folder.root, "not-there.log"))
        assertTrue(tail.readNew().isEmpty())
    }
}
