package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `libbitcoind.so -version`: the command and environment that keep Core out of an
 * unwritable home, and the reading of its answer.
 */
class VersionProbeTest {

    /** What the bundled daemon printed on a desktop host. */
    private val goodOutput = """
        Bitcoin Core daemon version v32.1.0 bitcoind
        Copyright (C) 2009-2026 The Bitcoin Core developers

        Please contribute if you find Bitcoin Core useful. Visit
        <https://bitcoincore.org/> for further information about the software.
    """.trimIndent()

    /** What it printed on the phone before the environment was fixed. */
    private val failedOutput =
        "Error: filesystem error: cannot create directories: Read-only file system [\"/.bitcoin\"]"

    @Test
    fun readsTheVersionOutOfTheOutput() {
        assertEquals("v32.1.0", VersionProbe.parse(goodOutput))
        // The line may be preceded by whatever the daemon warns about first.
        assertEquals("v32.1.0", VersionProbe.parse("$failedOutput\n\n$goodOutput"))
        // A release candidate or a tagged build keeps its suffix in the token.
        assertEquals("v28.0.0rc1", VersionProbe.parse("Bitcoin Core version v28.0.0rc1\n"))
        assertNull(VersionProbe.parse(failedOutput))
        assertNull(VersionProbe.parse(""))
    }

    @Test
    fun recognisesACachedVersion() {
        assertTrue(VersionProbe.isVersion("v32.1.0"))
        assertTrue(VersionProbe.isVersion("Bitcoin Core daemon version v32.1.0 bitcoind"))
        // The cached error of an earlier build is not a version and is dropped.
        assertFalse(VersionProbe.isVersion(failedOutput))
        assertFalse(VersionProbe.isVersion(""))
        assertFalse(VersionProbe.isVersion("bundled Core"))
    }

    @Test
    fun handsTheDaemonAWritableHomeAndDatadir() {
        val home = File("/data/user/0/com.jbitmstr.pocketcore/files")
        val tmp = File("/data/user/0/com.jbitmstr.pocketcore/cache")
        val inherited = mapOf("PATH" to "/system/bin", "HOME" to "/", "TMPDIR" to "/tmp")
        val prepared = VersionProbe.environment(inherited, home, tmp)
        assertEquals(home.absolutePath, prepared["HOME"])
        assertEquals(tmp.absolutePath, prepared["TMPDIR"])
        assertEquals("/system/bin", prepared["PATH"])

        val command = VersionProbe.command("/data/app/lib/arm64/libbitcoind.so", File("/data/user/0/com.jbitmstr.pocketcore/files/node"))
        assertEquals(listOf("/data/app/lib/arm64/libbitcoind.so", "-version",
            "-datadir=/data/user/0/com.jbitmstr.pocketcore/files/node"), command)
        assertTrue(command.none { it == "-conf=" })
    }
}
