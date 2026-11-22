package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bounded log reads of [NodePaths].
 *
 * debug.log grows into hundreds of megabytes during a sync. Reading it into one
 * array to find one line is how the sixth review's OutOfMemoryError happens, so
 * every read is capped - and the cap must keep the window that matters: the
 * bytes a start wrote after its offset, where Core's bind line sits.
 */
class NodePathsReadTest {

    private fun tempFile(text: String): File {
        val file = File.createTempFile("pocketcore-log", ".log")
        file.deleteOnExit()
        file.writeText(text)
        return file
    }

    @Test
    fun readFromStopsAtTheCap() {
        val file = tempFile("A".repeat(3 * 1024 * 1024))
        val text = NodePaths.readFrom(file, 0L)
        assertEquals(NodePaths.MAX_LOG_READ_BYTES, text.length)
    }

    @Test
    fun readFromHonoursAnExplicitCap() {
        val file = tempFile("0123456789".repeat(1_000))
        assertEquals("0123456789", NodePaths.readFrom(file, 0L, maxBytes = 10))
    }

    @Test
    fun readFromStartsAtTheOffsetNotAtTheEndOfTheFile() {
        // 1.5 MB written before the start, then the bind line of this start: the
        // evidence lives at the beginning of the window, so a cap that kept the
        // tail instead would drop exactly the line being sought.
        val before = "filler line from an earlier run\n".repeat(50_000)
        val bind = "2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332\n"
        val file = tempFile(before + bind)
        val offset = before.toByteArray(Charsets.UTF_8).size.toLong()

        val window = NodePaths.readFrom(file, offset)
        assertTrue(window.startsWith("2026-11-22T05:12:09Z Binding RPC"))
        assertTrue(ListenerFallback.rpcBindConfirmed(window, 8332))
    }

    @Test
    fun aWindowLargerThanTheCapStillCarriesTheBindLine() {
        val before = "x".repeat(4096)
        val bind = "2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332\n"
        val noise = "block download progress\n".repeat(100_000)
        val file = tempFile(before + bind + noise)
        val offset = before.toByteArray(Charsets.UTF_8).size.toLong()

        val window = NodePaths.readFrom(file, offset)
        assertEquals(NodePaths.MAX_LOG_READ_BYTES, window.length)
        assertTrue(ListenerFallback.rpcBindConfirmed(window, 8332))
    }

    @Test
    fun readFromAnOffsetPastTheEndIsEmpty() {
        val file = tempFile("short")
        assertEquals("", NodePaths.readFrom(file, 10_000L))
    }

    @Test
    fun readFromAMissingFileIsEmpty() {
        assertEquals("", NodePaths.readFrom(File("/nonexistent/pocketcore/debug.log"), 0L))
    }

    @Test
    fun readTailReturnsTheLastLinesOfABigFile() {
        val file = tempFile((1..20_000).joinToString("\n") { "line $it" })
        val tail = NodePaths.readTail(file, 3)
        assertEquals(listOf("line 19998", "line 19999", "line 20000"), tail.lines())
    }

    @Test
    fun readTailDoesNotReadAWholeLargeFile() {
        // The window is fixed at 256 KB, so a megabyte of log produces the last
        // lines instead of the whole text: what BitcoindNode.outputTail() needs
        // after a long sync.
        val file = tempFile("y".repeat(1024 * 1024) + "\nlast line")
        val tail = NodePaths.readTail(file, 2)
        assertTrue(tail.endsWith("last line"))
        assertFalse(tail.length > 256 * 1024 + 64)
    }
}
