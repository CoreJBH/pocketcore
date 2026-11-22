package com.corejbh.pocketcore.core

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 integrity of the bundled daemon.
 *
 * `libbitcoind.so` is the one file this app executes, and it is executed as the
 * app's own UID with access to the datadir. The APK carries the hash it was
 * built with (see `BuildConfig.BITCOIND_SHA256`, computed by Gradle from
 * `jniLibs/arm64-v8a/libbitcoind.so`), so the extracted copy is hashed once per
 * build before it is ever run: a binary that was replaced, truncated or only
 * partly extracted at install time is refused instead of started.
 *
 * The answer is cached in a stamp file for the current build, because the file
 * does not change between starts of one installed build, and a 10.7 MB hash on
 * every start is unnecessary. The stamp records the build tag and the expected
 * hash, so a new build (a new versionCode, or a new daemon in the same one) can
 * never read an older build's verdict.
 *
 * Plain Kotlin over [File], so both hashing and the cache are unit tested.
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
     * A stamp in [stampFile] that already records this [buildTag] and hash is
     * taken as the answer without hashing again. [expectedSha256] is compared in
     * lowercase, so a hash written in either case verifies.
     */
    fun verify(file: File, expectedSha256: String, stampFile: File?, buildTag: String): Boolean {
        val expected = expectedSha256.trim().lowercase()
        if (!isDigest(expected)) return false
        if (!file.isFile) return false
        if (stampFile != null && stampMatches(stampFile, buildTag, expected)) return true
        val actual = sha256(file) ?: return false
        if (actual != expected) return false
        if (stampFile != null) {
            runCatching {
                stampFile.parentFile?.mkdirs()
                stampFile.writeText("$buildTag $expected\n")
            }
        }
        return true
    }

    /** True when [stampFile] records a verdict for exactly this build and hash. */
    fun stampMatches(stampFile: File, buildTag: String, expectedSha256: String): Boolean {
        if (!stampFile.isFile) return false
        val text = runCatching { stampFile.readText().trim() }.getOrNull() ?: return false
        return text == "$buildTag ${expectedSha256.lowercase()}"
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
