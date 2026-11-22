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
    fun aShrunkenLogIsReadFromTheStartInsteadOfTheOldOffset() {
        // -shrinkdebugfile=1 rewrites debug.log at startup, keeping its recent
        // tail: the file a start recorded a 1.5 MB offset in becomes 4 KB long.
        // Reading from the offset would return nothing, the bind line written
        // after the shrink would never be seen, and ListenerFallback would call
        // the port FOREIGN and kill the healthy node that just bound it.
        val before = "filler line from an earlier run\n".repeat(50_000)
        val file = tempFile(before)
        val offset = file.length()
        assertTrue(offset > 4_096L)

        val keptTail = "2026-11-22T05:10:00Z New best header\n"
        val bind = "2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332\n"
        file.writeText(keptTail + bind)
        assertTrue(file.length() < offset)

        val window = NodePaths.readFrom(file, offset)
        assertTrue(window.startsWith("2026-11-22T05:10:00Z"))
        assertTrue(ListenerFallback.rpcBindConfirmed(window, 8332))
        assertEquals(keptTail + bind, window)
    }

    @Test
    fun anOffsetPastTheEndReadsTheShrunkFileNotNothing() {
        val file = tempFile("short")
        assertEquals("short", NodePaths.readFrom(file, 10_000L))
    }

    @Test
    fun anEmptyTruncatedLogReadsAsEmpty() {
        // Core can truncate the log to nothing before it writes the next line;
        // there is simply no window, and the bind evidence stays missing for
        // this pass rather than a stale line vouching for the start.
        val file = tempFile("")
        assertEquals("", NodePaths.readFrom(file, 10_000L))
    }

    @Test
    fun aShrunkenLogIsStillCapped() {
        // The reset offset makes the whole current file the window, so the cap
        // still applies: a log that was truncated and then grew past a megabyte
        // cannot be read into one array.
        val file = tempFile("Z".repeat(3 * 1024 * 1024))
        val text = NodePaths.readFrom(file, 5_000_000L)
        assertEquals(NodePaths.MAX_LOG_READ_BYTES, text.length)
    }

    @Test
    fun readFromAMissingFileIsEmpty() {
        assertEquals("", NodePaths.readFrom(File("/nonexistent/pocketcore/debug.log"), 0L))
        assertFalse(NodePaths.readFromWindow(File("/nonexistent/pocketcore/debug.log"), 0L).fromStartAfterShrink)
    }

    // --- The window says how it had to be taken (eighth review) --------------

    @Test
    fun anOrdinaryWindowIsNotReportedAsAShrink() {
        // Bytes before the offset belong to an earlier run, so the window is
        // still this run's alone and its lines need no timestamp check.
        val file = tempFile("old line\n" + "2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332\n")
        val offset = "old line\n".toByteArray(Charsets.UTF_8).size.toLong()
        val window = NodePaths.readFromWindow(file, offset)
        assertFalse(window.fromStartAfterShrink)
        assertEquals("2026-11-22T05:12:09Z Binding RPC on address 127.0.0.1 port 8332\n", window.text)
    }

    @Test
    fun aWindowTakenFromTheStartOfAShrunkenFileSaysSo() {
        // The flag is what makes the caller demand a timestamp of every line: the
        // kept tail holds the previous run's bind line on the same port.
        val previousRun = "2026-11-22T05:10:02Z [http] Binding RPC on address 127.0.0.1 port 8332\n"
        val file = tempFile(previousRun)
        val offset = file.length() + 1_000
        val window = NodePaths.readFromWindow(file, offset)
        assertTrue(window.fromStartAfterShrink)
        assertEquals(previousRun, window.text)

        // That line alone passes the old check and must fail the dated one.
        val startMillis = 1_795_324_329_000L // 2026-11-22T05:12:09Z
        assertTrue(ListenerFallback.rpcBindConfirmed(window.text, 8332))
        assertFalse(ListenerFallback.rpcBindConfirmed(window.text, 8332, sinceMillis = startMillis))
    }

    @Test
    fun anOffsetExactlyAtTheEndIsNotAShrink() {
        val file = tempFile("0123456789")
        val window = NodePaths.readFromWindow(file, file.length())
        assertFalse(window.fromStartAfterShrink)
        assertEquals("", window.text)
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
