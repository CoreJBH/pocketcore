package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

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
        val path = "/data/app/com.corejbh.pocketcore-1/lib/arm64/libbitcoind.so"
        assertTrue(ProcScan.runsBinary("$path\u0000-datadir=/data/data/com.corejbh.pocketcore/files/node/data\u0000", binary))
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
        val path = "/data/app/com.corejbh.pocketcore-1/lib/arm64/libbitcoind.so"
        val daemon = "$path\u0000-datadir=/data/data/com.corejbh.pocketcore/files/node\u0000-conf=/x/bitcoin.conf\u0000"
        val probe = "$path\u0000-version\u0000-datadir=/data/data/com.corejbh.pocketcore/files/node\u0000"
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
            "/data/app/com.corejbh.pocketcore-1/lib/arm64/${NodePaths.BINARY_NAME}"
        } else {
            "/system/bin/sh"
        }
        File(dir, "cmdline").writeBytes("$binary\u0000-datadir=/data\u0000".toByteArray(Charsets.UTF_8))
        File(dir, "status").writeText("Name:\tbitcoind\n$uidLine\n")
    }

    // --- Socket ownership ---------------------------------------------------

    /** A /proc/net/tcp table in the kernel's own layout. */
    private val tcpTable = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:2085 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 123456 1 0 100 0 0 10 0
           1: 0100007F:2096 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 777888 1 0 100 0 0 10 0
           2: 0100007F:2085 0100007F:D4A2 01 00000000:00000000 00:00000000 00000000 10123        0 999000 1 0 20 4 30 10 -1
    """.trimIndent()

    @Test
    fun readsTheInodesOfListeningSocketsOnOnePort() {
        // 0x2085 = 8325, the RPC port this test's daemon holds.
        assertEquals(setOf("123456"), ProcScan.listenInodes(tcpTable, 8325))
        assertEquals(setOf("777888"), ProcScan.listenInodes(tcpTable, 8342))
        // The established connection on the same port is not a listener.
        assertTrue(ProcScan.listenInodes(tcpTable, 54434).isEmpty())
        assertTrue(ProcScan.listenInodes("sl local_address\n", 8325).isEmpty())
        assertTrue(ProcScan.listenInodes("", 8325).isEmpty())
    }

    @Test
    fun readsTheInodeOutOfASocketFdLink() {
        assertEquals("123456", ProcScan.socketInode("socket:[123456]"))
        assertNull(ProcScan.socketInode("/data/data/com.corejbh.pocketcore/files/node/data"))
        assertNull(ProcScan.socketInode("anon_inode:[eventfd]"))
        assertNull(ProcScan.socketInode("socket:[]"))
    }

    @Test
    fun aDaemonOwnsTheListeningSocketItHolds() {
        val root = Files.createTempDirectory("procscan").toFile()
        try {
            val pidDir = File(root, "4242").apply { mkdirs() }
            File(pidDir, "net").mkdirs()
            File(pidDir, "net/tcp").writeText(tcpTable)
            File(pidDir, "fd").mkdirs()
            // The daemon holds fd 7 on socket 123456.
            Files.createSymbolicLink(File(pidDir, "fd/7").toPath(), Paths.get("socket:[123456]"))
            // ... and an unrelated pipe, to show the scan reads the links.
            Files.createSymbolicLink(File(pidDir, "fd/8").toPath(), Paths.get("pipe:[42]"))

            val tables = listOf(File(pidDir, "net/tcp").readText(), null)
            assertEquals(SocketOwner.OURS, ProcScan.listenerOwner(tables, File(pidDir, "fd"), 8325))
            // A port nobody listens on in this namespace.
            assertEquals(
                SocketOwner.NOT_LISTENING,
                ProcScan.listenerOwner(tables, File(pidDir, "fd"), 8332)
            )
            assertTrue(ProcScan.ownsSocket(File(pidDir, "fd"), setOf("123456")))
            assertFalse(ProcScan.ownsSocket(File(pidDir, "fd"), setOf("555555")))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The regression this check exists for: the port is listening, but not in
     * the daemon's own fd table, so the credentials must not be sent to it.
     */
    @Test
    fun aListenerTheDaemonDoesNotHoldIsForeign() {
        val root = Files.createTempDirectory("procscan").toFile()
        try {
            val pidDir = File(root, "4242").apply { mkdirs() }
            File(pidDir, "net").mkdirs()
            File(pidDir, "net/tcp").writeText(tcpTable)
            File(pidDir, "fd").mkdirs()
            Files.createSymbolicLink(File(pidDir, "fd/3").toPath(), Paths.get("socket:[777888]"))

            assertEquals(
                SocketOwner.FOREIGN,
                ProcScan.listenerOwner(listOf(tcpTable), File(pidDir, "fd"), 8325)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnreadableProcEntryIsNotAVerdict() {
        val root = Files.createTempDirectory("procscan").toFile()
        try {
            // No table could be read at all.
            assertEquals(
                SocketOwner.UNKNOWN,
                ProcScan.listenerOwner(listOf(null, null), File(root, "fd"), 8325)
            )
            // A listener exists, but the fd table cannot be listed.
            assertEquals(
                SocketOwner.UNKNOWN,
                ProcScan.listenerOwner(listOf(tcpTable), File(root, "missing-fd"), 8325)
            )
            assertFalse(ProcScan.ownsSocket(File(root, "missing-fd"), setOf("123456")))
        } finally {
            root.deleteRecursively()
        }
    }

    // --- When did the daemon start (eighth review) ----------------------------

    /**
     * `starttime` is field 22, counted after the parenthesised command name - and
     * that name can itself hold spaces, which is why the fields are counted from
     * the last `)` and not by splitting the line.
     */
    @Test
    fun readsTheStartTimeOfAProcess() {
        val stat = "4242 (libbitcoind.so) S 1 4242 4242 0 -1 4194560 100 0 0 0 5 6 0 0 20 0 8 0 987654 1234 56 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        assertEquals(987_654L, ProcScan.statStartTicks(stat))
        // A binary name with a space and a parenthesis in it does not shift the
        // fields: they are read after the last ')'.
        val odd = "4242 (bitcoind (old) build) S 1 4242 4242 0 -1 4194560 100 0 0 0 5 6 0 0 20 0 8 0 123456 1234 56"
        assertEquals(123_456L, ProcScan.statStartTicks(odd))
    }

    @Test
    fun aStatThatDoesNotParseHasNoStartTime() {
        assertNull(ProcScan.statStartTicks(""))
        assertNull(ProcScan.statStartTicks("4242 libbitcoind.so S 1"))
        assertNull(ProcScan.statStartTicks("4242 (x) S"))
        assertNull(ProcScan.statStartTicks("4242 (x) S 1 2 3"))
    }

    @Test
    fun theStartTimeIsTheBootMomentPlusTheTicks() {
        // 100 Hz clock: 12 345 ticks after boot is 123 450 ms.
        val now = 1_795_324_329_000L
        val uptime = 4_000_000L
        val boot = now - uptime
        assertEquals(boot + 123_450L, ProcScan.processStartMillis(12_345L, now, uptime))
        assertEquals(boot, ProcScan.processStartMillis(0L, now, uptime))
    }
}
