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
            assertFalse(BinaryIntegrity.verify(file, "0".repeat(64)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun refusesAMissingFileAndAMalformedDigest() {
        val missing = File(Files.createTempDirectory("daemon").toFile(), "gone.so")
        assertNull(BinaryIntegrity.sha256(missing))
        assertFalse(BinaryIntegrity.verify(missing, "a".repeat(64)))
        val file = tempFile(content)
        try {
            assertFalse("a short hash is not a digest", BinaryIntegrity.isDigest("abc123"))
            assertFalse(BinaryIntegrity.verify(file, "abc123"))
            assertTrue(BinaryIntegrity.isDigest("A".repeat(64).lowercase()))
            // An uppercase digest is the same digest.
            assertTrue(BinaryIntegrity.verify(file, contentSha256.uppercase()))
        } finally {
            file.delete()
        }
    }

    /**
     * Regression (versionCode 20): the verdict is never cached on disk. A file
     * that verified once and was replaced afterwards must fail the next check,
     * even though the same build asks again.
     */
    @Test
    fun aFileReplacedAfterAVerifiedCheckFailsTheNextOne() {
        val file = tempFile(content)
        try {
            assertTrue(BinaryIntegrity.verify(file, contentSha256))
            file.writeText("$(content) tampered")
            assertFalse(
                "the second check must look at the file, not at an older verdict",
                BinaryIntegrity.verify(file, contentSha256)
            )
        } finally {
            file.delete()
        }
    }
}
