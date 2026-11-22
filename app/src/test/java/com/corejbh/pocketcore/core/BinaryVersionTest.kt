package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The order of the version lookup: verify the binary, then run it.
 *
 * versionCode 19 fixed a daemon that was hashed before a start and executed
 * unhashed for its version line. These tests hold the two properties the review
 * asked for: a binary that fails the check is never run, and the answer is
 * cached per build so an unchanged daemon is not started for a version on every
 * app launch.
 */
class BinaryVersionTest {

    private val stamp: File = Files.createTempFile("binary-version", ".txt").toFile().also { it.delete() }

    private var prepared = 0
    private var preparedProblem: String? = null
    private var detected = 0
    private var detectedVersion: String? = "v32.1.0"

    private fun reader(buildTag: String = "19 abc") = BinaryVersion(
        stamp = stamp,
        buildTag = buildTag,
        prepare = {
            prepared++
            preparedProblem
        },
        detect = {
            detected++
            detectedVersion
        }
    )

    @Test
    fun aBinaryThatFailsTheCheckIsNotExecuted() {
        preparedProblem = "The bundled bitcoind does not match its checksum (6becfe26...)."
        val version = reader().read()
        assertEquals("", version)
        assertEquals("the check ran", 1, prepared)
        assertEquals("an unverified binary must not be run", 0, detected)
        assertFalse("nothing is cached about an unverified binary", stamp.exists())
    }

    @Test
    fun theCheckRunsBeforeTheBinary() {
        val order = ArrayList<String>()
        val version = BinaryVersion(
            stamp = stamp,
            buildTag = "19 abc",
            prepare = { order.add("prepare"); null },
            detect = { order.add("detect"); "v32.1.0" }
        ).read()
        assertEquals("v32.1.0", version)
        assertEquals(listOf("prepare", "detect"), order)
    }

    @Test
    fun theAnswerIsCachedForTheBuildTag() {
        assertEquals("v32.1.0", reader().read())
        assertTrue(stamp.isFile)
        // A second lookup inside the same build reads the stamp: neither the
        // check nor the process runs again.
        assertEquals("v32.1.0", reader().read())
        assertEquals(1, prepared)
        assertEquals(1, detected)
    }

    @Test
    fun anotherBuildOrAnErrorLineIsNotTrusted() {
        // Written by a previous versionCode: the daemon may have changed with it.
        stamp.writeText("18 abc\nv32.0.0\n")
        assertEquals("v32.1.0", reader().read())
        assertEquals("a new build checks and probes again", 1, detected)

        // A stamp that holds an error line instead of a version is discarded too.
        stamp.writeText("19 abc\nError: filesystem error\n")
        detectedVersion = "v32.1.0"
        assertEquals("v32.1.0", reader().read())
        assertEquals("an old value is re-checked", 2, detected)
    }

    @Test
    fun anEmptyAnswerIsNotCached() {
        detectedVersion = null
        assertEquals("", reader().read())
        assertEquals(1, detected)
        assertFalse(stamp.exists())
    }
}
