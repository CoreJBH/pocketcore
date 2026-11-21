package com.jbitmstr.pocketcore.core

import java.io.File

/**
 * Asks the bundled daemon for its version.
 *
 * `libbitcoind.so -version` runs through Core's early argument handling, which
 * resolves a data directory from `$HOME` before it prints anything. An Android
 * app process has no writable home, so the run failed with
 * `Error: filesystem error: cannot create directories: ... [/.bitcoin]` and the
 * version never reached the screen. The probe therefore hands the process a
 * writable `HOME`, a `TMPDIR` and an explicit `-datadir` under the app's own
 * files, and reads the version line out of the output instead of trusting the
 * first line, which is where Core writes such an error.
 *
 * Pure Kotlin, so the command, the environment and the parsing are unit tested.
 * The `-version` flag is also what tells the process scan that this process is
 * not the daemon (see [ProcScan.runsDaemon]).
 */
object VersionProbe {

    /** Marker of the line Core prints for `-version`. */
    const val MARKER = "Bitcoin Core"

    private val VERSION_TOKEN = Regex("v\\d+\\.\\d+\\.\\d+[^\\s]*")

    fun command(binaryPath: String, dataDir: File): List<String> = listOf(
        binaryPath,
        ProcScan.VERSION_FLAG,
        "-datadir=${dataDir.absolutePath}"
    )

    /**
     * [inherited] with `HOME` and `TMPDIR` pointed at writable app paths. Both
     * are replaced, never appended to, so the daemon cannot fall back to `/`.
     */
    fun environment(inherited: Map<String, String>, home: File, tmp: File): Map<String, String> =
        inherited + mapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath
        )

    /**
     * The version the daemon reported, e.g. `v32.1.0`.
     *
     * The first line that names Bitcoin Core and a version wins; anything before
     * it (a filesystem error, a warning) is skipped. Returns null when the output
     * holds no version line at all.
     */
    fun parse(output: String): String? {
        for (line in output.lineSequence()) {
            val text = line.trim()
            if (!text.contains(MARKER)) continue
            if (!text.contains("version", ignoreCase = true)) continue
            return VERSION_TOKEN.find(text)?.value ?: text
        }
        return null
    }

    /** True when [text] is already a version this probe would report. */
    fun isVersion(text: String): Boolean =
        text.isNotBlank() && (VERSION_TOKEN.matches(text) || parse(text) != null)
}
