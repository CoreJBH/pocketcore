package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCardTest {

    private fun chain(
        blocks: Long,
        headers: Long,
        progress: Double,
        initialBlockDownload: Boolean
    ) = BlockchainInfo(
        chain = "main",
        blocks = blocks,
        headers = headers,
        verificationProgress = progress,
        initialBlockDownload = initialBlockDownload,
        sizeOnDisk = 620_000_000L,
        pruned = true,
        pruneHeight = blocks - 550,
        pruneTargetSize = 576_716_800L,
        bestBlockHash = "0000000000000000000".repeat(3),
        difficulty = 1.4e14,
        time = 1_763_600_000L
    )

    private fun peers(count: Int) = NetworkInfo(
        version = 320100,
        subversion = "/Satoshi:32.1.0(POCKETCORE)/",
        protocolVersion = 70016,
        connections = count,
        connectionsIn = 2,
        connectionsOut = count - 2,
        networkActive = true,
        relayFee = 0.00001,
        timeOffset = 0
    )

    private fun running(chain: BlockchainInfo, headerSync: HeaderSync? = null) = NodeUiState(
        status = NodeStatus.RUNNING,
        blockchain = chain,
        headerSync = headerSync,
        network = peers(12)
    )

    @Test
    fun caughtUpCardReadsLikeTheReferenceApp() {
        val card = NotificationCard.of(
            running(chain(blocks = 976_724, headers = 976_724, progress = 0.99999, initialBlockDownload = false))
        )
        assertEquals("Synced - Block #976,724", card.title)
        assertEquals("Fully synced", card.subText)
        assertNull(card.progress)
        assertEquals(0xFF0369A1.toInt(), card.color)
    }

    @Test
    fun caughtUpCardHoldsTheDetailsForTheExpandedView() {
        val card = NotificationCard.of(
            running(chain(blocks = 976_724, headers = 976_724, progress = 0.99999, initialBlockDownload = false))
        )
        assertEquals("976,724 blocks verified - 12 peers - Mainnet", card.expanded)
    }

    @Test
    fun downloadCardShowsPercentAndBlockOnOneLine() {
        val card = NotificationCard.of(
            running(chain(blocks = 123_456, headers = 976_000, progress = 0.1234, initialBlockDownload = true))
        )
        assertEquals("12.34% - Block #123,456", card.title)
        assertEquals("Syncing", card.subText)
        assertEquals(1_234, card.progress)
        assertEquals(0xFF1E3A8A.toInt(), card.color)
        assertEquals("Block 123,456 of 976,000 - 12 peers - Mainnet", card.expanded)
    }

    @Test
    fun headerPreSyncIsReadFromTheLogInsteadOfZeroPercent() {
        val card = NotificationCard.of(
            running(
                chain(blocks = 0, headers = 0, progress = 0.0, initialBlockDownload = true),
                headerSync = HeaderSync(height = 812_345, percent = 42.10, presync = true)
            )
        )
        assertEquals("42.10% - Header #812,345", card.title)
        assertEquals("Pre-syncing", card.subText)
        assertEquals(4_210, card.progress)
        assertEquals(0xFF1E3A8A.toInt(), card.color)
    }

    @Test
    fun headerDownloadWithoutALogLineStillSaysWhatIsHappening() {
        val card = NotificationCard.of(
            running(chain(blocks = 0, headers = 0, progress = 0.0, initialBlockDownload = true))
        )
        assertEquals("Downloading block headers", card.title)
        assertEquals("Pre-syncing", card.subText)
        assertNull(card.progress)
    }

    @Test
    fun committedHeaderChainDropsThePreSyncWord() {
        val card = NotificationCard.of(
            running(
                chain(blocks = 0, headers = 0, progress = 0.0, initialBlockDownload = true),
                headerSync = HeaderSync(height = 976_000, percent = 99.99, presync = false)
            )
        )
        assertEquals("Syncing", card.subText)
        assertEquals(9_999, card.progress)
    }

    @Test
    fun almostSyncedIsStillASyncingCard() {
        val card = NotificationCard.of(
            running(chain(blocks = 975_000, headers = 976_000, progress = 0.9990, initialBlockDownload = true))
        )
        assertEquals("99.90% - Block #975,000", card.title)
        assertEquals("Syncing", card.subText)
        assertEquals(9_990, card.progress)
    }

    @Test
    fun pausedCardKeepsTheLastHeightInTheHeaderRow() {
        val state = NodeUiState(
            status = NodeStatus.WAITING,
            detail = "Waiting for Wi-Fi",
            blockchain = chain(blocks = 976_724, headers = 976_724, progress = 0.99999, initialBlockDownload = false)
        )
        val card = NotificationCard.of(state)
        assertEquals("Paused - Block #976,724", card.title)
        assertEquals("Waiting for Wi-Fi", card.subText)
        assertNull(card.progress)
        assertEquals(0xFF0F172A.toInt(), card.color)
    }

    @Test
    fun pausedCardWithoutAChainSaysPaused() {
        val card = NotificationCard.of(NodeUiState(status = NodeStatus.WAITING))
        assertEquals("Node paused", card.title)
        assertEquals("Paused", card.subText)
    }

    @Test
    fun otherStatesHaveTheirOwnHeaderWord() {
        assertEquals("Stopped", NotificationCard.of(NodeUiState(status = NodeStatus.STOPPED)).subText)
        assertEquals("Starting", NotificationCard.of(NodeUiState(status = NodeStatus.STARTING)).subText)
        assertEquals("Stopping", NotificationCard.of(NodeUiState(status = NodeStatus.STOPPING)).subText)
        assertEquals("Error", NotificationCard.of(NodeUiState(status = NodeStatus.ERROR)).subText)
    }

    @Test
    fun titleNeverCarriesTheAppNameTwice() {
        val cards = listOf(
            running(chain(blocks = 976_724, headers = 976_724, progress = 0.99999, initialBlockDownload = false)),
            running(chain(blocks = 1, headers = 2, progress = 0.5, initialBlockDownload = true)),
            NodeUiState(status = NodeStatus.STARTING),
            NodeUiState(status = NodeStatus.ERROR, detail = "bitcoind stopped unexpectedly")
        ).map { NotificationCard.of(it) }
        for (card in cards) {
            assertEquals(false, card.title.contains("POCKETCORE", ignoreCase = true))
            assertEquals(false, card.subText.contains("POCKETCORE", ignoreCase = true))
        }
    }
}
