package com.jbitmstr.pocketcore.core

/**
 * Header synchronization progress, recovered from `debug.log`.
 *
 * While bitcoind is pre-synchronizing block headers it has not committed a
 * header chain yet, so `getblockchaininfo` reports `headers: 0` and
 * `verificationprogress: 0` even though the node is downloading at full speed.
 * Core writes the real position only to the log, once every few headers:
 *
 * ```
 * Pre-synchronizing blockheaders, height: 812345 (~42.10%)
 * Synchronizing blockheaders, height: 812345 (~42.10%)
 * ```
 *
 * The percentage is Core's own estimate: it divides the accepted header height
 * by the height the chain would have today at one block per ten minutes.
 */
data class HeaderSync(
    /** Height of the newest accepted header. */
    val height: Long,
    /** Core's estimate of the header chain position, 0.00 - 100.00. */
    val percent: Double,
    /** True for the pre-sync phase, before the header chain is committed. */
    val presync: Boolean
) {
    /** [percent] as the 0.0 - 1.0 fraction a progress bar takes. */
    val fraction: Double get() = (percent / 100.0).coerceIn(0.0, 1.0)

    companion object {
        private val LINE = Regex(
            """(Pre-)?synchronizing blockheaders, height: (\d+) \(~([0-9]+(?:\.[0-9]+)?)%\)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The most recent header progress line in [text], or null when the log
         * holds none (the node has not downloaded a header yet).
         */
        fun latest(text: String): HeaderSync? {
            val match = LINE.findAll(text).lastOrNull() ?: return null
            val height = match.groupValues[2].toLongOrNull() ?: return null
            val percent = match.groupValues[3].toDoubleOrNull() ?: return null
            if (!percent.isFinite()) return null
            return HeaderSync(
                height = height,
                percent = percent.coerceIn(0.0, 100.0),
                presync = match.groupValues[1].isNotEmpty()
            )
        }
    }
}
