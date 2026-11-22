package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AssumeUTXO checkpoints and the export decisions built on them.
 *
 * `loadtxoutset` accepts a snapshot only when it matches a checkpoint compiled
 * into the daemon, so an export for another phone is dumped at the newest one and
 * then checked against it (versionCode 20).
 */
class AssumeUtxoTest {

    private val mainnet = AssumeUtxo.latest(BitcoinNetwork.MAINNET)
    private val signet = AssumeUtxo.latest(BitcoinNetwork.SIGNET)
    private val testnet4 = AssumeUtxo.latest(BitcoinNetwork.TESTNET4)

    @Test
    fun theNewestCheckpointOfEachNetworkIsTheOneTheReviewNamed() {
        assertEquals(965_000L, mainnet.height)
        assertEquals(150_000L, testnet4.height)
        assertEquals(320_000L, signet.height)
    }

    @Test
    fun everyCheckpointIsAWellFormedHash() {
        BitcoinNetwork.entries.forEach { network ->
            val checkpoints = AssumeUtxo.checkpoints(network)
            assertTrue("$network must have checkpoints", checkpoints.isNotEmpty())
            checkpoints.forEach { checkpoint ->
                assertEquals("block hash of ${checkpoint.height}", 64, checkpoint.blockHash.length)
                assertEquals("utxo set hash of ${checkpoint.height}", 64, checkpoint.utxoSetHash.length)
                assertTrue(
                    "hashes of ${checkpoint.height} are hex",
                    (checkpoint.blockHash + checkpoint.utxoSetHash).all { it in "0123456789abcdef" }
                )
                assertTrue("tx count of ${checkpoint.height}", checkpoint.chainTxCount > 0)
            }
            // Lowest first, and the last one is what [AssumeUtxo.latest] returns.
            val heights = checkpoints.map { it.height }
            assertEquals(heights.sorted(), heights)
            assertEquals(heights.max(), AssumeUtxo.latest(network).height)
        }
    }

    /** The mainnet list is the one in Core's `chainparams.cpp`, all five. */
    @Test
    fun mainnetCarriesEveryCheckpointCoreCompilesIn() {
        assertEquals(
            listOf(840_000L, 880_000L, 910_000L, 935_000L, 965_000L),
            AssumeUtxo.checkpoints(BitcoinNetwork.MAINNET).map { it.height }
        )
        assertEquals(
            listOf(90_000L, 120_000L, 150_000L),
            AssumeUtxo.checkpoints(BitcoinNetwork.TESTNET4).map { it.height }
        )
    }

    @Test
    fun aCheckpointNamesItsOwnBlock() {
        assertEquals(
            "00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9",
            mainnet.blockHash
        )
        assertEquals(
            "4a8d794337118c0c615b574f817c7306c687584a537184b8d233df42bf477ec2",
            mainnet.utxoSetHash
        )
    }

    @Test
    fun aChainThatHasNotReachedTheCheckpointIsRefusedWithItsHeight() {
        val problem = checkpointExportProblem(
            syncedBlocks = 900_000,
            pruned = false,
            pruneHeight = 0,
            checkpoint = mainnet
        )
        assertTrue(problem!!.contains("synced to block 900,000"))
        assertTrue(problem.contains("965,000"))
    }

    @Test
    fun aPrunedNodeBelowTheCheckpointIsRefusedWithTheStorageFix() {
        val problem = checkpointExportProblem(
            syncedBlocks = 1_000_000,
            pruned = true,
            pruneHeight = 970_000,
            checkpoint = mainnet
        )
        assertTrue(problem!!.contains("pruned"))
        assertTrue(problem.contains("Full (prune=0)"))
    }

    /** A pruned node that still holds the checkpoint block can dump it. */
    @Test
    fun aPrunedNodeThatStillHoldsTheCheckpointBlockIsAllowed() {
        assertNull(
            checkpointExportProblem(
                syncedBlocks = 1_000_000,
                pruned = true,
                pruneHeight = 900_000,
                checkpoint = mainnet
            )
        )
        assertNull(
            checkpointExportProblem(
                syncedBlocks = 965_000,
                pruned = false,
                pruneHeight = 0,
                checkpoint = mainnet
            )
        )
    }

    @Test
    fun aDumpAtTheCheckpointVerifies() {
        val dump = TxOutSetDump(
            path = "/tmp/snapshot.dat",
            baseHeight = mainnet.height,
            baseHash = mainnet.blockHash,
            txoutsetHash = mainnet.utxoSetHash,
            coinsWritten = 60_000_000,
            chainTx = mainnet.chainTxCount
        )
        assertTrue(checkpointVerified(dump, mainnet))
        assertTrue("Core writes the hash in lowercase, an uppercase reply is the same value",
            checkpointVerified(dump.copy(txoutsetHash = mainnet.utxoSetHash.uppercase()), mainnet))
    }

    @Test
    fun aDumpAtTheTipDoesNotVerify() {
        val tipDump = TxOutSetDump(
            path = "/tmp/snapshot.dat",
            baseHeight = 1_000_000,
            baseHash = "00".repeat(32),
            txoutsetHash = mainnet.utxoSetHash,
            coinsWritten = 60_000_000,
            chainTx = mainnet.chainTxCount
        )
        assertFalse("a tip dump is not the checkpoint", checkpointVerified(tipDump, mainnet))
        assertFalse(
            "another UTXO set at the same height is not the checkpoint either",
            checkpointVerified(
                tipDump.copy(baseHeight = mainnet.height, txoutsetHash = "11".repeat(32)),
                mainnet
            )
        )
    }

    @Test
    fun aPrunedFailureGetsTheStorageGuidance() {
        val explained = describeDumpFailure(
            "Block not available (pruned data)",
            mainnet
        )
        assertTrue(explained.contains("Full (prune=0)"))
        assertTrue(explained.contains("965,000"))
        assertTrue("Core's own line stays", explained.contains("pruned data"))
    }

    @Test
    fun anotherFailureKeepsCoresMessage() {
        assertEquals(
            "Invalid parameter",
            describeDumpFailure("Invalid parameter", mainnet)
        )
    }
}
