package com.corejbh.pocketcore.core

/**
 * One AssumeUTXO checkpoint compiled into Bitcoin Core v32.1.
 *
 * The values are the ones in Core's own `src/kernel/chainparams.cpp`
 * (`m_assumeutxo_data`): the height, the block hash at that height, the hash of
 * the serialized UTXO set as of that block, and the chain transaction count.
 */
data class AssumeUtxoCheckpoint(
    val height: Long,
    val blockHash: String,
    val utxoSetHash: String,
    val chainTxCount: Long
)

/**
 * The AssumeUTXO checkpoints the bundled daemon accepts.
 *
 * `loadtxoutset` takes a `.dat` only when the UTXO set it holds is one of the
 * checkpoints compiled into the daemon - height, block hash and the serialized
 * set hash all have to match. A snapshot dumped at the node's own tip
 * (`rollback = "latest"`) is therefore loadable by the phone that made it, and
 * by nothing else: the next node compares it with a checkpoint it does not
 * have. An export meant for another phone is dumped at a checkpoint height
 * instead (`dumptxoutset` takes a `rollback` height), which is what
 * [latest] is for (versionCode 20).
 *
 * The values below are taken from the same daemon this app ships (Core v32.1),
 * and they are not merely trusted: an export compares the checkpoint block hash
 * with the node's own block at that height before the dump, and the dump's
 * `txoutset_hash` with [AssumeUtxoCheckpoint.utxoSetHash] afterwards. A file
 * that passes both is the snapshot another phone's `loadtxoutset` accepts.
 *
 * Mainnet and testnet4 are the networks the review named; signet carries
 * checkpoints of its own and is listed for the same reason. Testnet3 is not in
 * this app.
 */
object AssumeUtxo {

    private val MAINNET = listOf(
        AssumeUtxoCheckpoint(
            height = 840_000,
            blockHash = "0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5",
            utxoSetHash = "a2a5521b1b5ab65f67818e5e8eccabb7171a517f9e2382208f77687310768f96",
            chainTxCount = 991_032_194
        ),
        AssumeUtxoCheckpoint(
            height = 880_000,
            blockHash = "000000000000000000010b17283c3c400507969a9c2afd1dcf2082ec5cca2880",
            utxoSetHash = "dbd190983eaf433ef7c15f78a278ae42c00ef52e0fd2a54953782175fbadcea9",
            chainTxCount = 1_145_604_538
        ),
        AssumeUtxoCheckpoint(
            height = 910_000,
            blockHash = "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821",
            utxoSetHash = "4daf8a17b4902498c5787966a2b51c613acdab5df5db73f196fa59a4da2f1568",
            chainTxCount = 1_226_586_151
        ),
        AssumeUtxoCheckpoint(
            height = 935_000,
            blockHash = "0000000000000000000147034958af1652b2b91bba607beacc5e72a56f0fb5ee",
            utxoSetHash = "e4b90ef9eae834f56c4b64d2d50143cee10ad87994c614d7d04125e2a6025050",
            chainTxCount = 1_305_397_408
        ),
        AssumeUtxoCheckpoint(
            height = 965_000,
            blockHash = "00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9",
            utxoSetHash = "4a8d794337118c0c615b574f817c7306c687584a537184b8d233df42bf477ec2",
            chainTxCount = 1_429_611_231
        )
    )

    private val SIGNET = listOf(
        AssumeUtxoCheckpoint(
            height = 160_000,
            blockHash = "0000003ca3c99aff040f2563c2ad8f8ec88bd0fd6b8f0895cfaf1ef90353a62c",
            utxoSetHash = "fe0a44309b74d6b5883d246cb419c6221bcccf0b308c9b59b7d70783dbdf928a",
            chainTxCount = 2_289_496
        ),
        AssumeUtxoCheckpoint(
            height = 290_000,
            blockHash = "0000000577f2741bb30cd9d39d6d71b023afbeb9764f6260786a97969d5c9ac0",
            utxoSetHash = "97267e000b4b876800167e71b9123f1529d13b14308abec2888bbd2160d14545",
            chainTxCount = 28_547_497
        ),
        AssumeUtxoCheckpoint(
            height = 320_000,
            blockHash = "0000000740ae66b284da84387dcfa14d7b1385b0bad482005ba4e770ea6c4b95",
            utxoSetHash = "1aaf72ecb376cc16957fbb8d5d406bfd6e3165510e2fc83879b6d14cd20b4462",
            chainTxCount = 32_079_110
        )
    )

    private val TESTNET4 = listOf(
        AssumeUtxoCheckpoint(
            height = 90_000,
            blockHash = "0000000002ebe8bcda020e0dd6ccfbdfac531d2f6a81457191b99fc2df2dbe3b",
            utxoSetHash = "784fb5e98241de66fdd429f4392155c9e7db5c017148e66e8fdbc95746f8b9b5",
            chainTxCount = 11_347_043
        ),
        AssumeUtxoCheckpoint(
            height = 120_000,
            blockHash = "000000000bd2317e51b3c5794981c35ba894ce27d3e772d5c39ecd9cbce01dc8",
            utxoSetHash = "10b05d05ad468d0971162e1b222a4aa66caca89da2bb2a93f8f37fb29c4794b0",
            chainTxCount = 14_141_057
        ),
        AssumeUtxoCheckpoint(
            height = 150_000,
            blockHash = "0000000000d9877342754dea8ec1eb24631517d38e3443c370465ee53a8b7434",
            utxoSetHash = "ca068cae50679d7c947454bbe4f0e6aeec1fbe2c6c2735a08bb988623649f950",
            chainTxCount = 14_810_011
        )
    )

    /** Every checkpoint of [network], lowest height first. */
    fun checkpoints(network: BitcoinNetwork): List<AssumeUtxoCheckpoint> = when (network) {
        BitcoinNetwork.MAINNET -> MAINNET
        BitcoinNetwork.SIGNET -> SIGNET
        BitcoinNetwork.TESTNET4 -> TESTNET4
    }

    /**
     * The newest checkpoint of [network] - the height an export for another
     * phone is dumped at. [checkpoints] is ordered lowest first.
     */
    fun latest(network: BitcoinNetwork): AssumeUtxoCheckpoint = checkpoints(network).last()
}

/**
 * Why this node cannot be exported at [checkpoint], or null when it can.
 *
 * `dumptxoutset` with a rollback height reads the block at that height and walks
 * the chainstate back to it, so two facts are required up front and both have a
 * reason a user can act on:
 *
 *  - the node has to have synced at least that far, or there is no such block
 *    in its chain yet;
 *  - a pruned node has to still hold the block. Pruning deletes everything below
 *    the keep horizon, and a checkpoint far below it cannot be rolled back to.
 *    The fix is the STORAGE setting (Full, `prune=0`), not a retry.
 *
 * Pure, so both refusals are unit tested.
 */
internal fun checkpointExportProblem(
    syncedBlocks: Long,
    pruned: Boolean,
    pruneHeight: Long,
    checkpoint: AssumeUtxoCheckpoint
): String? {
    if (syncedBlocks < checkpoint.height) {
        return "This node has synced to block ${count(syncedBlocks)}, and the official checkpoint is " +
            "at block ${count(checkpoint.height)}. A snapshot for another phone is dumped at that " +
            "checkpoint, so let the node sync to ${count(checkpoint.height)} first."
    }
    if (pruned && pruneHeight > checkpoint.height) {
        return "This node is pruned: it only keeps blocks from ${count(pruneHeight)} upward, and " +
            "block ${count(checkpoint.height)} has already been deleted. Core cannot dump a snapshot " +
            "at the official checkpoint from a pruned node. Set STORAGE to Full (prune=0), let the " +
            "node sync to ${count(checkpoint.height)}, and export again."
    }
    return null
}

/**
 * True when [dump] is exactly the checkpoint [checkpoint] describes.
 *
 * This is the proof that matters for handing the file to another phone: Core's
 * `loadtxoutset` accepts a snapshot only when the height and the serialized
 * UTXO set hash match a checkpoint it carries, so a dump that matches both is
 * loadable there (versionCode 20).
 */
internal fun checkpointVerified(dump: TxOutSetDump, checkpoint: AssumeUtxoCheckpoint): Boolean =
    dump.baseHeight == checkpoint.height &&
        dump.txoutsetHash.equals(checkpoint.utxoSetHash, ignoreCase = true)

/**
 * A failed `dumptxoutset` explained, or the message as Core sent it.
 *
 * The two failures a user can actually run into are the pruned one (the block
 * the checkpoint needs is gone) and a rollback Core otherwise refused; both get
 * their own sentence so the screen says what to do instead of showing Core's
 * line alone (versionCode 20).
 */
internal fun describeDumpFailure(message: String, checkpoint: AssumeUtxoCheckpoint): String = when {
    message.contains("pruned", true) || message.contains("Block not available", true) ->
        "The node could not roll back to block ${count(checkpoint.height)}: that block is no " +
            "longer on disk. A pruned node deletes old blocks, so a snapshot at the official " +
            "checkpoint cannot be dumped from it. Set STORAGE to Full (prune=0), let the node " +
            "sync to ${count(checkpoint.height)}, and export again. Core said: $message"
    message.contains("rollback", true) ->
        "Core refused the rollback to block ${count(checkpoint.height)}, the height of the " +
            "official checkpoint. Core said: $message"
    else -> message
}

/** "965,000" style formatting for the messages above. */
private fun count(value: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)
