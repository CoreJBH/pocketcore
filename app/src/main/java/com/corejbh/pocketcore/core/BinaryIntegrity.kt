package com.corejbh.pocketcore.core

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 integrity of the bundled daemon.
 *
 * `libbitcoind.so` is the one file this app executes, and it is executed as the
 * app's own UID with access to the datadir. The APK carries the hash it was
 * built with (see `BuildConfig.BITCOIND_SHA256`, computed by Gradle from
 * `jniLibs/arm64-v8a/libbitcoind.so`), so the extracted copy is hashed against
 * it before it is ever run: a binary that was replaced, truncated or only
 * partly extracted at install time is refused instead of started.
 *
 * The file is hashed again at every check, never answered from a stamp on disk.
 * A stamp records what one run of the app saw, and the next cold start - the one
 * that follows an update, a re-extraction or a file replaced while the device sat
 * idle - read that verdict instead of looking at the file it was about: the stamp
 * was the one place the check could be satisfied without the file matching it
 * (versionCode 20). A 10.7 MB hash takes well under a second and happens once
 * per start, not per poll.
 *
 * Plain Kotlin over [File], so hashing is unit tested.
 */
object BinaryIntegrity {

    /** Lowercase hex SHA-256 of [file], or null when it cannot be read. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    /** True when [expected] is a 64 character hex digest. */
    fun isDigest(expected: String): Boolean =
        expected.length == 64 && expected.all { it.isDigit() || it in 'a'..'f' }

    /**
     * True when [file] hashes to [expectedSha256].
     *
     * [expectedSha256] is compared in lowercase, so a hash written in either
     * case verifies. Nothing is cached: the answer is always this file's.
     */
    fun verify(file: File, expectedSha256: String): Boolean {
        val expected = expectedSha256.trim().lowercase()
        if (!isDigest(expected)) return false
        if (!file.isFile) return false
        return sha256(file) == expected
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    private const val HEX = "0123456789abcdef"
}
