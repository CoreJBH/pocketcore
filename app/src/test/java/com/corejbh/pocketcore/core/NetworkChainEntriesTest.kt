package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The network-scoped wipe behind a Full (prune=0) rebuild: only the selected
 * network's chain data may be in the list, never the other networks' chains.
 */
class NetworkChainEntriesTest {

    private fun newDirectory(): java.nio.file.Path = Files.createTempDirectory("pocketcore-wipe")

    @Test
    fun mainnetTakesEverythingExceptTheOtherNetworks() {
        val root = newDirectory()
        listOf("blocks", "chainstate", "signet", "testnet4").forEach { Files.createDirectory(root.resolve(it)) }
        Files.write(root.resolve("debug.log"), byteArrayOf(1))
        Files.write(root.resolve(".lock"), byteArrayOf(1))

        val entries = NodePaths.networkChainEntries(root.toFile(), BitcoinNetwork.MAINNET)
            .map { it.name }
            .sorted()

        assertEquals(listOf(".lock", "blocks", "chainstate", "debug.log"), entries)
    }

    @Test
    fun aTestNetworkTakesOnlyItsOwnSubdirectory() {
        val root = newDirectory()
        listOf("blocks", "signet", "testnet4").forEach { Files.createDirectory(root.resolve(it)) }

        assertEquals(
            listOf("signet"),
            NodePaths.networkChainEntries(root.toFile(), BitcoinNetwork.SIGNET).map { it.name }
        )
        assertEquals(
            listOf("testnet4"),
            NodePaths.networkChainEntries(root.toFile(), BitcoinNetwork.TESTNET4).map { it.name }
        )
    }

    @Test
    fun aMissingDirectoryDeletesNothing() {
        val missing = newDirectory().resolve("nowhere").toFile()
        assertTrue(NodePaths.networkChainEntries(missing, BitcoinNetwork.MAINNET).isEmpty())
    }
}
