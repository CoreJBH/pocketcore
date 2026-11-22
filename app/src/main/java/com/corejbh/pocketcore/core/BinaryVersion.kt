package com.corejbh.pocketcore.core

import android.util.Log
import java.io.File

/**
 * The bundled daemon's version, answered without running a binary this build
 * does not vouch for.
 *
 * `version()` used to run `libbitcoind.so -version` directly. That is still
 * executing the file in nativeLibraryDir, which is the one place on a modern
 * Android where an app may run its own native code - and the executable bit
 * there is what makes a replaced file dangerous. The ninth review found the
 * start path gated by [BinaryIntegrity] while this path was not: a daemon that
 * failed its checksum was refused a start and executed anyway for its version
 * line, once per app start, from the same code that then reports the node
 * "ready".
 *
 * [prepare] is the same check [BitcoindNode.prepareBinary] gives a start
 * (existence, size, the build's SHA-256, the executable bit). When it returns a
 * problem, nothing is executed and the version is empty: a version string is not
 * worth running an unverified binary, and the screen shows the problem through
 * the start path instead.
 *
 * The answer is cached in [stamp] under [buildTag] (the versionCode and the
 * expected hash), so a new build re-probes and an unchanged one does not pay for
 * a process on every start.
 *
 * The three steps are injected, so the order - and that a failed check stops
 * before the run - is unit tested without a daemon.
 */
internal class BinaryVersion(
    private val stamp: File,
    private val buildTag: String,
    private val prepare: () -> String?,
    private val detect: () -> String?
) {

    fun read(): String {
        cached()?.let { return it }
        val problem = prepare()
        if (problem != null) {
            Log.w(TAG, "Not running the bundled bitcoind for its version: $problem")
            return ""
        }
        val detected = detect()?.trim().orEmpty()
        if (detected.isEmpty()) return ""
        runCatching { stamp.writeText("$buildTag\n$detected\n") }
        return detected
    }

    /**
     * The stored answer, when it belongs to this build and reads as a version.
     *
     * Anything else - an older build's entry, the daemon's error line instead of
     * a version - is deleted rather than trusted, and the binary is asked again
     * (after the same check as a first run).
     */
    private fun cached(): String? {
        if (!stamp.exists()) return null
        val lines = runCatching { stamp.readLines() }.getOrDefault(emptyList())
        if (lines.firstOrNull()?.trim() == buildTag) {
            val text = lines.getOrNull(1)?.trim().orEmpty()
            if (VersionProbe.isVersion(text)) return text
        }
        runCatching { stamp.delete() }
        return null
    }

    private companion object {
        const val TAG = "PocketCore"
    }
}
