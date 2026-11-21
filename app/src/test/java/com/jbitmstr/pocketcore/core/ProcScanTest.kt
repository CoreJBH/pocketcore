package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Reading /proc: exact UID comparison, argv matching and stray discovery. */
class ProcScanTest {

    private val binary = NodePaths.BINARY_NAME

    @Test
    fun readsRealUidAsAWholeToken() {
        assertEquals(10123, ProcScan.realUid(listOf("Name:\tbitcoind", "Uid:\t10123\t10123\t10123\t10123")))
        // The effective/saved ids are ignored: a set-uid process is still ours by
        // its real UID.
        assertEquals(10123, ProcScan.realUid(listOf("Uid:\t10123\t20000\t20000\t10123")))
        assertNull(ProcScan.realUid(listOf("Name:\tbitcoind")))
        assertNull(ProcScan.realUidOfLine("Uid:"))
        assertNull(ProcScan.realUidOfLine(null))
    }

    /**
     * Regression: `uidLine.contains(myUid.toString())` matched UID 101234 for
     * UID 10123, so the app killed another app's daemon.
     */
    @Test
    fun uidIsNotMatchedByPrefix() {
        val foreign = listOf("Uid:\t101234\t101234\t101234\t101234")
        assertFalse(ProcScan.runsAs(foreign, 10123))
        assertTrue(ProcScan.runsAs(foreign, 101234))
        val shorter = listOf("Uid:\t1012\t1012\t1012\t1012")
        assertFalse(ProcScan.runsAs(shorter, 10123))
    }

    @Test
    fun matchesTheBinaryByArgumentName() {
        val path = "/data/app/com.jbitmstr.pocketcore-1/lib/arm64/libbitcoind.so"
        assertTrue(ProcScan.runsBinary("$path\u0000-datadir=/data/data/com.jbitmstr.pocketcore/files/node/data\u0000", binary))
        assertTrue(ProcScan.runsBinary("$path\u0000-version\u0000", binary))
        assertFalse(ProcScan.runsBinary("/data/app/other/lib/arm64/libbitcoind2.so\u0000", binary))
        assertFalse(ProcScan.runsBinary("$path.backup\u0000", binary))
        assertFalse(ProcScan.runsBinary("", binary))
    }

    /**
     * The app's own `-version` probe runs the same binary; it is not the daemon
     * and must not be stopped or killed as one.
     */
    @Test
    fun tellsTheDaemonApartFromTheVersionProbe() {
        val path = "/data/app/com.jbitmstr.pocketcore-1/lib/arm64/libbitcoind.so"
        val daemon = "$path\u0000-datadir=/data/data/com.jbitmstr.pocketcore/files/node\u0000-conf=/x/bitcoin.conf\u0000"
        val probe = "$path\u0000-version\u0000-datadir=/data/data/com.jbitmstr.pocketcore/files/node\u0000"
        assertTrue(ProcScan.runsBinary(probe, binary))
        assertTrue(ProcScan.runsDaemon(daemon, binary))
        assertFalse(ProcScan.runsDaemon(probe, binary))
    }

    @Test
    fun listsOnlyThisAppsDaemons() {
        val root = Files.createTempDirectory("procscan").toFile()
        try {
            // 4242: our UID, the daemon -> found.
            process(root, 4242, "Uid:\t10123\t10123\t10123\t10123", daemon = true)
            // 4243: our UID, but not the daemon binary.
            process(root, 4243, "Uid:\t10123\t10123\t10123\t10123", daemon = false)
            // 4244: the daemon binary, but another app's UID -> never touched.
            process(root, 4244, "Uid:\t10124\t10124\t10124\t10124", daemon = true)
            // 4245: a UID whose digits start with ours.
            process(root, 4245, "Uid:\t101234\t101234\t101234\t101234", daemon = true)
            // 4246: our UID and binary, but excluded (this process).
            process(root, 4246, "Uid:\t10123\t10123\t10123\t10123", daemon = true)
            // A non-numeric /proc entry is not a process.
            File(root, "self").mkdirs()

            val found = ProcScan.daemonPids(root, binary, uid = 10123, exclude = setOf(4246))
            assertEquals(listOf(4242), found)

            // Without a UID filter (test helper) the binary decides.
            assertEquals(
                setOf(4242, 4244, 4245, 4246),
                ProcScan.daemonPids(root, binary, uid = null).toSet()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableProcEntriesAreSkipped() {
        val root = Files.createTempDirectory("procscan").toFile()
        try {
            // A pid directory with no files at all: a process that just exited.
            File(root, "77").mkdirs()
            assertTrue(ProcScan.daemonPids(root, binary, uid = 10123).isEmpty())
            assertTrue(ProcScan.daemonPids(File(root, "missing"), binary, uid = 10123).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun process(root: File, pid: Int, uidLine: String, daemon: Boolean) {
        val dir = File(root, pid.toString()).apply { mkdirs() }
        val binary = if (daemon) {
            "/data/app/com.jbitmstr.pocketcore-1/lib/arm64/${NodePaths.BINARY_NAME}"
        } else {
            "/system/bin/sh"
        }
        File(dir, "cmdline").writeBytes("$binary\u0000-datadir=/data\u0000".toByteArray(Charsets.UTF_8))
        File(dir, "status").writeText("Name:\tbitcoind\n$uidLine\n")
    }
}
