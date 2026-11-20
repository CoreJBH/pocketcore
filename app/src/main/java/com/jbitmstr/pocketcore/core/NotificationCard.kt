package com.jbitmstr.pocketcore.core

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The text and color of one foreground notification card.
 *
 * The card follows the layout of the mobile node apps the user compares against:
 * the app name, a short state ([subText]) and the time in the header row, and a
 * single content line ([title]) that reads like
 * `Synced - Block #976,724`. [expanded] holds the detail that is only worth
 * reading when the card is opened.
 *
 * Kept free of Android types so the exact wording can be checked by unit tests.
 */
data class NotificationCard(
    val title: String,
    val subText: String,
    val expanded: String,
    /** Position on the [PROGRESS_SCALE] bar, or null to hide the bar. */
    val progress: Int? = null,
    val color: Int
) {
    companion object {
        /** Opaque deep cobalt card while headers or blocks are downloading. */
        const val COLOR_SYNCING = 0xFF1E3A8A.toInt()

        /** Opaque ocean blue once the node is caught up. */
        const val COLOR_SYNCED = 0xFF0369A1.toInt()

        /** Dark slate-navy while the node is paused, starting or stopping. */
        const val COLOR_IDLE = 0xFF0F172A.toInt()

        /** Horizontal progress resolution of the colorized card (100.00%). */
        const val PROGRESS_SCALE = 10_000

        fun of(state: NodeUiState): NotificationCard {
            val network = state.config.network.label
            val chain = state.blockchain
            return when (state.status) {
                NodeStatus.STOPPED -> NotificationCard(
                    title = "Node stopped",
                    subText = "Stopped",
                    expanded = "$network node is not running",
                    color = COLOR_IDLE
                )
                NodeStatus.WAITING -> NotificationCard(
                    title = chain?.let { "Paused - Block #${formatNumber(it.blocks)}" } ?: "Node paused",
                    subText = state.detail ?: "Paused",
                    expanded = "${state.detail ?: "Paused"}. The node starts again " +
                        "automatically when the condition is met.",
                    color = COLOR_IDLE
                )
                NodeStatus.STARTING -> NotificationCard(
                    title = "Starting node",
                    subText = "Starting",
                    expanded = "$network - loading bitcoind",
                    color = COLOR_IDLE
                )
                NodeStatus.STOPPING -> NotificationCard(
                    title = "Stopping node",
                    subText = "Stopping",
                    expanded = "Waiting for bitcoind to shut down cleanly",
                    color = COLOR_IDLE
                )
                NodeStatus.ERROR -> NotificationCard(
                    title = "Node error",
                    subText = "Error",
                    expanded = state.detail ?: "bitcoind stopped unexpectedly",
                    color = COLOR_IDLE
                )
                NodeStatus.RUNNING -> running(state, chain, network)
            }
        }

        private fun running(
            state: NodeUiState,
            chain: BlockchainInfo?,
            network: String
        ): NotificationCard {
            if (chain == null) {
                return NotificationCard(
                    title = "Waiting for the first RPC reply",
                    subText = "Starting",
                    expanded = "$network - the node is running",
                    color = COLOR_IDLE
                )
            }
            val peers = state.network?.connections ?: 0
            val caughtUp = !chain.initialBlockDownload && chain.verificationProgress >= 0.999
            return when {
                caughtUp -> NotificationCard(
                    title = "Synced - Block #${formatNumber(chain.blocks)}",
                    subText = "Fully synced",
                    expanded = "${formatNumber(chain.blocks)} blocks verified - " +
                        "$peers peers - $network",
                    color = COLOR_SYNCED
                )
                chain.headers == 0L -> headers(state, peers, network)
                else -> {
                    val fraction = chain.verificationProgress.coerceIn(0.0, 1.0)
                    NotificationCard(
                        title = "${formatPercent(fraction * 100.0)} - Block #${formatNumber(chain.blocks)}",
                        subText = "Syncing",
                        expanded = "Block ${formatNumber(chain.blocks)} of " +
                            "${formatNumber(chain.headers)} - $peers peers - $network",
                        progress = (fraction * PROGRESS_SCALE).roundToInt().coerceIn(0, PROGRESS_SCALE),
                        color = COLOR_SYNCING
                    )
                }
            }
        }

        /**
         * Header download. Core reports `headers: 0` and `0.00%` over RPC until
         * the header chain is committed, so the position comes from the last
         * `Pre-synchronizing blockheaders` line of debug.log.
         */
        private fun headers(state: NodeUiState, peers: Int, network: String): NotificationCard {
            val headerSync = state.headerSync
                ?: return NotificationCard(
                    title = "Downloading block headers",
                    subText = "Pre-syncing",
                    expanded = "Waiting for the first headers - $peers peers - $network",
                    color = COLOR_SYNCING
                )
            return NotificationCard(
                title = "${formatPercent(headerSync.percent)} - Header #${formatNumber(headerSync.height)}",
                subText = if (headerSync.presync) "Pre-syncing" else "Syncing",
                expanded = "Block headers at ${formatPercent(headerSync.percent)} " +
                    "(#${formatNumber(headerSync.height)}) - $peers peers - $network",
                progress = (headerSync.fraction * PROGRESS_SCALE).roundToInt()
                    .coerceIn(0, PROGRESS_SCALE),
                color = COLOR_SYNCING
            )
        }

        private fun formatNumber(value: Long): String =
            NumberFormat.getIntegerInstance(Locale.US).format(value)

        /** A 0 - 100 percentage with two decimals, as Core prints it. */
        private fun formatPercent(percent: Double): String =
            String.format(Locale.US, "%.2f%%", percent.coerceIn(0.0, 100.0))
    }
}
