package com.corejbh.pocketcore.core

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/**
 * Result of `dumptxoutset` - the UTXO set written to a file for `loadtxoutset`.
 *
 * Bitcoin Core 32.1 (`src/rpc/blockchain.cpp`) answers with `coins_written`,
 * `base_hash`, `base_height`, `path`, `txoutset_hash` and `nchaintx`. Those are
 * the facts the export card shows: at which block the snapshot was taken, how
 * many coins it holds and the hash another node will report after loading it.
 */
data class TxOutSetDump(
    /** Absolute path Core wrote, as it reported it. */
    val path: String,
    /** Height the snapshot is based on: the tip for `latest`, or the rollback height. */
    val baseHeight: Long,
    /** Hash of the block the snapshot is based on. */
    val baseHash: String,
    /** Hash of the UTXO set contents; `loadtxoutset` reports the same value. */
    val txoutsetHash: String,
    /** Coins in the snapshot. */
    val coinsWritten: Long,
    /** Transactions in the chain up to and including the base block. */
    val chainTx: Long
) {
    /** One line for the screen, with the three facts a reader compares. */
    fun summary(): String = buildString {
        append("Snapshot at block ")
        append(count(baseHeight))
        append(": ")
        append(count(coinsWritten))
        append(if (coinsWritten == 1L) " coin" else " coins")
        append(", UTXO set hash ")
        append(short(txoutsetHash))
        append(".")
    }

    companion object {
        private val numbers = NumberFormat.getIntegerInstance(Locale.US)

        private fun count(value: Long): String = numbers.format(value)

        private fun short(hash: String): String =
            if (hash.length > 20) "${hash.take(10)}...${hash.takeLast(8)}" else hash

        /**
         * Reads the reply. [path] is optional in the app's own use (the caller
         * already knows the file it asked for), the other numbers keep their
         * `base_height`/`base_hash` names from Core.
         */
        fun from(json: JSONObject) = TxOutSetDump(
            path = json.optString("path", ""),
            baseHeight = json.optLong("base_height"),
            baseHash = json.optString("base_hash", ""),
            txoutsetHash = json.optString("txoutset_hash", ""),
            coinsWritten = json.optLong("coins_written"),
            chainTx = json.optLong("nchaintx")
        )
    }
}

/**
 * Parameters for `dumptxoutset`.
 *
 * The type argument cannot be left out: Core 32.1 answers `-8 Invalid snapshot
 * type ""` when neither `latest`/`rollback` nor a rollback option is given, so
 * `latest` is always written out for a tip snapshot. A rollback height travels
 * in the named options object, which Core accepts with the type left empty
 * (`dumptxoutset <path> "" {"rollback": <height>}`) - verified against
 * Bitcoin Core 32.1 on regtest.
 */
fun dumpTxOutSetParams(path: String, rollbackHeight: Long? = null): List<Any?> =
    if (rollbackHeight == null) {
        listOf(path, "latest")
    } else {
        listOf(path, "", JSONObject().put("rollback", rollbackHeight))
    }
