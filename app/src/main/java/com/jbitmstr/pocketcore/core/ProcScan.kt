package com.jbitmstr.pocketcore.core

import java.io.File

/**
 * The parts of /proc the app has to read before it touches a process.
 *
 * Android gives every app its own UID, and /proc entries of another UID are not
 * readable from here: a process this scan can see is a process of this app. The
 * two questions asked below decide whether a busy RPC port may be handed our
 * credentials, and which PIDs are strays of the bundled daemon.
 *
 * Plain Kotlin over [File] paths, so the parsing can be unit tested against a
 * fake proc directory.
 */
object ProcScan {

    /**
     * Real UID of a process, from its `status` lines.
     *
     * The `Uid:` line holds four values (real, effective, saved, filesystem).
     * The comparison is on whole tokens: a substring test made UID 10123 accept
     * a process of UID 101234.
     */
    fun realUid(statusLines: List<String>): Int? =
        realUidOfLine(statusLines.firstOrNull { it.startsWith("Uid:") })

    /** Real UID of a single `Uid:\t<real>\t<effective>\t<saved>\t<fs>` line. */
    fun realUidOfLine(line: String?): Int? {
        val values = line?.substringAfter(':', "")?.trim().orEmpty()
        if (values.isEmpty()) return null
        return values.split(WHITESPACE).firstOrNull()?.toIntOrNull()
    }

    /** True when [statusLines] belong to a process running under [uid]. */
    fun runsAs(statusLines: List<String>, uid: Int): Boolean = realUid(statusLines) == uid

    /**
     * True when one of the NUL separated argv entries of a process names
     * [binaryName]. The daemon is started by its absolute path, so the entry is
     * compared by file name.
     */
    fun runsBinary(cmdline: String, binaryName: String): Boolean =
        cmdline.split('\u0000').any { token ->
            token.isNotEmpty() && token.substringAfterLast('/') == binaryName
        }

    /**
     * PIDs under [procRoot] that run [binaryName] as [uid].
     *
     * With [uid] null the UID filter is skipped, which is only useful for tests.
     * Entries this process cannot read (another UID, a process that just died)
     * are skipped rather than treated as a match.
     */
    fun daemonPids(
        procRoot: File,
        binaryName: String,
        uid: Int?,
        exclude: Set<Int> = emptySet()
    ): List<Int> {
        val entries = procRoot.listFiles { file -> file.name.all(Char::isDigit) } ?: return emptyList()
        val pids = ArrayList<Int>(2)
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid in exclude) continue
            val cmdline = readText(File(entry, "cmdline")) ?: continue
            if (!runsBinary(cmdline, binaryName)) continue
            if (uid != null) {
                val status = readLines(File(entry, "status")) ?: continue
                if (!runsAs(status, uid)) continue
            }
            pids.add(pid)
        }
        return pids
    }

    private fun readText(file: File): String? = runCatching { file.readBytes() }
        .getOrNull()
        ?.toString(Charsets.UTF_8)

    private fun readLines(file: File): List<String>? = runCatching { file.readLines() }.getOrNull()

    private val WHITESPACE = Regex("[\\s\u0000]+")
}
