package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The checksum check that runs before the bundled daemon is executed.
 *
 * The digest of the test content below is the published SHA-256 of the string
 * "PocketCore daemon", so the hash itself is checked against a value that does
 * not come from this implementation.
 */
class BinaryIntegrityTest {

    private val content = "PocketCore daemon"

    /** `printf 'PocketCore daemon' | sha256sum`, a value from outside this code. */
    private val contentSha256 = "7a0ea47f51c908d360a69d0e4d55706a6c5444d7c485f29c19e6f5048dd1d259"

    private fun tempFile(text: String): File {
        val file = Files.createTempFile("daemon", ".so").toFile()
        file.writeText(text)
        return file
    }

    @Test
    fun hashesTheFileContent() {
        val file = tempFile(content)
        try {
            val digest = BinaryIntegrity.sha256(file)
            assertEquals(contentSha256, digest)
            assertEquals(64, digest!!.length)
            assertTrue(digest.all { it in "0123456789abcdef" })
            // The same bytes hash the same way every time.
            assertEquals(digest, BinaryIntegrity.sha256(file))
            assertEquals(digest, BinaryIntegrity.sha256(tempFile(content)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun refusesAFileWhoseHashDiffers() {
        val file = tempFile("$(content) tampered")
        try {
            // Any digest of the right shape is accepted as input; a mismatch is
            // still a mismatch.
            assertFalse(
                BinaryIntegrity.verify(
                    file = file,
                    expectedSha256 = "0".repeat(64),
                    stampFile = null,
                    buildTag = "12"
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun refusesAMissingFileAndAMalformedDigest() {
        val missing = File(Files.createTempDirectory("daemon").toFile(), "gone.so")
        assertNull(BinaryIntegrity.sha256(missing))
        assertFalse(BinaryIntegrity.verify(missing, "a".repeat(64), null, "12"))
        val file = tempFile(content)
        try {
            assertFalse("a short hash is not a digest", BinaryIntegrity.isDigest("abc123"))
            assertFalse(BinaryIntegrity.verify(file, "abc123", null, "12"))
            assertTrue(BinaryIntegrity.isDigest("A".repeat(64).lowercase()))
        } finally {
            file.delete()
        }
    }

    /**
     * The verdict is cached for one build only: the same build reuses it, and a
     * new versionCode (or a different daemon in the same one) does not.
     */
    @Test
    fun cachesTheVerdictPerBuild() {
        val file = tempFile(content)
        val stamp = File(Files.createTempDirectory("stamp").toFile(), "binary-integrity.txt")
        try {
            val actual = BinaryIntegrity.sha256(file)!!
            assertFalse(BinaryIntegrity.stampMatches(stamp, "12 x", actual))
            assertTrue(BinaryIntegrity.verify(file, actual, stamp, "12 x"))
            assertTrue("the verdict was written", stamp.isFile)
            assertTrue(BinaryIntegrity.stampMatches(stamp, "12 x", actual))
            // A different build tag is not served by the cached line.
            assertFalse(BinaryIntegrity.stampMatches(stamp, "13 x", actual))
            // An uppercase digest is the same digest.
            assertTrue(BinaryIntegrity.verify(file, actual.uppercase(), stamp, "13 x"))
        } finally {
            file.delete()
            stamp.parentFile?.deleteRecursively()
        }
    }

    /** The cache must not turn a changed file into a pass for another build. */
    @Test
    fun aChangedFileFailsWhenTheCacheBelongsToAnotherBuild() {
        val file = tempFile(content)
        val stamp = File(Files.createTempDirectory("stamp").toFile(), "binary-integrity.txt")
        try {
            val actual = BinaryIntegrity.sha256(file)!!
            assertTrue(BinaryIntegrity.verify(file, actual, stamp, "12 x"))
            file.writeText("$(content) tampered")
            assertFalse(BinaryIntegrity.verify(file, actual, stamp, "13 x"))
        } finally {
            file.delete()
            stamp.parentFile?.deleteRecursively()
        }
    }
}
