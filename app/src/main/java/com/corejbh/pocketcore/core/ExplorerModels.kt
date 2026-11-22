package com.corejbh.pocketcore.core

import org.json.JSONArray
import org.json.JSONObject

/** One block, either fully loaded or header-only when its body was pruned. */
data class BlockInfo(
    val hash: String,
    val height: Long,
    val confirmations: Long,
    val time: Long,
    val version: Long,
    val merkleRoot: String,
    val nonce: Long,
    val bits: String,
    val difficulty: Double,
    val previousHash: String?,
    val nextHash: String?,
    val size: Long?,
    val strippedSize: Long?,
    val weight: Long?,
    val txCount: Int,
    val headerOnly: Boolean,
    val transactions: List<TxSummary>
) {
    companion object {
        fun fromBlock(json: JSONObject): BlockInfo {
            val transactions = json.optJSONArray("tx")
            return BlockInfo(
                hash = json.optString("hash"),
                height = json.optLong("height"),
                confirmations = json.optLong("confirmations"),
                time = json.optLong("time"),
                version = json.optLong("version"),
                merkleRoot = json.optString("merkleroot"),
                nonce = json.optLong("nonce"),
                bits = json.optString("bits"),
                difficulty = json.optDouble("difficulty", 0.0),
                previousHash = json.optStringOrNull("previousblockhash"),
                nextHash = json.optStringOrNull("nextblockhash"),
                size = json.optLongOrNull("size"),
                strippedSize = json.optLongOrNull("strippedsize"),
                weight = json.optLongOrNull("weight"),
                txCount = json.optInt("nTx", transactions?.length() ?: 0),
                headerOnly = false,
                // Every transaction listed here is part of this confirmed block:
                // `getblock` does not repeat the block hash or the confirmation
                // count inside each entry, so [TxSummary.from] alone would read
                // them as mempool transactions and the explorer would label a
                // block's contents "MEMPOOL".
                transactions = TxSummary.list(transactions).map { it.copy(inMempool = false) }
            )
        }

        fun fromHeader(json: JSONObject): BlockInfo = BlockInfo(
            hash = json.optString("hash"),
            height = json.optLong("height"),
            confirmations = json.optLong("confirmations"),
            time = json.optLong("time"),
            version = json.optLong("version"),
            merkleRoot = json.optString("merkleroot"),
            nonce = json.optLong("nonce"),
            bits = json.optString("bits"),
            difficulty = json.optDouble("difficulty", 0.0),
            previousHash = json.optStringOrNull("previousblockhash"),
            nextHash = json.optStringOrNull("nextblockhash"),
            size = null,
            strippedSize = null,
            weight = null,
            txCount = json.optInt("nTx", 0),
            headerOnly = true,
            transactions = emptyList()
        )
    }
}

/**
 * One input of a transaction, resolved as far as the node can resolve it.
 *
 * [valueSats] is the amount in satoshis, the unit the fee is computed in (see
 * [BtcAmount]); [value] is the same amount in BTC for the screen.
 */
data class TxInput(
    val prevTxid: String,
    val previousOutput: Int,
    val address: String?,
    val valueSats: Long?,
    val coinbase: Boolean
) {
    /** Amount of the spent output in BTC, or null when Core did not resolve it. */
    val value: Double? get() = valueSats?.let { BtcAmount.toBtc(it) }

    companion object {
        fun from(json: JSONObject): TxInput {
            val coinbase = json.has("coinbase") || json.optBoolean("is_coinbase", false)
            val prevout = json.optJSONObject("prevout")
            val script = prevout?.optJSONObject("scriptPubKey")
            return TxInput(
                prevTxid = json.optString("txid", if (coinbase) "Coinbase" else ""),
                previousOutput = json.optInt("vout", -1),
                address = script?.addressText(),
                valueSats = prevout?.let { BtcAmount.fromJson(it, "value") },
                coinbase = coinbase
            )
        }
    }
}

/**
 * One output of a transaction.
 *
 * Like [TxInput], the amount is kept in satoshis ([valueSats]) and shown in BTC
 * ([value]): a `Double` per output added up across a transaction is where the
 * total and the fee used to drift.
 */
data class TxOutput(
    val index: Int,
    val address: String?,
    val valueSats: Long,
    val type: String?,
    val scriptHex: String?
) {
    /** Amount in BTC. */
    val value: Double get() = BtcAmount.toBtc(valueSats)

    companion object {
        fun from(json: JSONObject): TxOutput {
            val script = json.optJSONObject("scriptPubKey")
            return TxOutput(
                index = json.optInt("n"),
                address = script?.addressText(),
                // An output without a readable value counts as zero: the total
                // then understates rather than inventing an amount.
                valueSats = BtcAmount.fromJson(json, "value") ?: 0L,
                type = script?.optStringOrNull("type"),
                scriptHex = script?.optStringOrNull("hex")
            )
        }
    }
}

/** A transaction as shown in the explorer. */
data class TxSummary(
    val txid: String,
    val version: Long,
    val size: Long,
    val vsize: Long,
    val weight: Long,
    val locktime: Long,
    val confirmations: Long,
    val blockHash: String?,
    val blockTime: Long?,
    val inMempool: Boolean,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    /** Sum of the outputs, in satoshis - the total the fee is subtracted from. */
    val totalOutSats: Long,
    /** Input total minus output total, in satoshis; null when a prevout is missing. */
    val feeSats: Long?,
    val isCoinbase: Boolean
) {
    val inputCount: Int get() = inputs.size
    val outputCount: Int get() = outputs.size

    /** Sum of the outputs, in BTC. */
    val totalOut: Double get() = BtcAmount.toBtc(totalOutSats)

    /** Fee in BTC, derived from [feeSats]. */
    val fee: Double? get() = feeSats?.let { BtcAmount.toBtc(it) }

    companion object {
        fun from(json: JSONObject): TxSummary {
            val inputs = buildList {
                val array = json.optJSONArray("vin") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(TxInput.from(it)) }
                }
            }
            val outputs = buildList {
                val array = json.optJSONArray("vout") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(TxOutput.from(it)) }
                }
            }
            // Integer satoshi arithmetic ([BtcAmount]): outputs are summed, and
            // the fee is the inputs' total minus that sum, with no binary
            // fraction to round. A transaction whose prevouts are exactly its
            // outputs therefore has a fee of 0 sat, not of -5e-17 BTC.
            val totalOutSats = outputs.sumOf { it.valueSats }
            val feeSats = if (inputs.isNotEmpty() && inputs.all { it.valueSats != null && !it.coinbase }) {
                inputs.sumOf { it.valueSats!! } - totalOutSats
            } else {
                // A prevout the node could not resolve leaves the inputs' total
                // unknown, so there is no fee to show rather than a wrong one.
                null
            }
            return TxSummary(
                txid = json.optString("txid"),
                version = json.optLong("version"),
                size = json.optLong("size"),
                vsize = json.optLong("vsize", json.optLong("size")),
                weight = json.optLong("weight"),
                locktime = json.optLong("locktime"),
                confirmations = json.optLong("confirmations"),
                blockHash = json.optStringOrNull("blockhash"),
                blockTime = json.optLongOrNull("blocktime") ?: json.optLongOrNull("time"),
                inMempool = !json.has("blockhash") && !json.has("confirmations"),
                inputs = inputs,
                outputs = outputs,
                totalOutSats = totalOutSats,
                feeSats = feeSats,
                isCoinbase = inputs.any { it.coinbase }
            )
        }

        /** Verbatim list order is the block's transaction order. */
        fun list(array: JSONArray?): List<TxSummary> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(from(it)) }
                }
            }
        }
    }
}

/** One unspent output found by a watch-only descriptor scan. */
data class ScannedUtxo(
    val txid: String,
    val vout: Int,
    val scriptHex: String?,
    val amount: Double,
    val height: Long,
    val descriptor: String?
) {
    /**
     * `scantxoutset` reports the script as hex, so the address is rendered here
     * for the network the node runs on.
     */
    fun address(network: BitcoinNetwork): String? {
        val resolved = scriptHex?.let { AddressCodec.fromScriptPubKey(it, network) }
        if (resolved != null) return resolved
        val desc = descriptor ?: return null
        if (desc.startsWith("addr(")) return desc.removePrefix("addr(").substringBefore(')')
        return null
    }

    companion object {
        fun list(array: JSONArray?): List<ScannedUtxo> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val script = item.opt("scriptPubKey")
                    val hex = when (script) {
                        is String -> script
                        is JSONObject -> script.optStringOrNull("hex")
                        else -> null
                    }
                    add(
                        ScannedUtxo(
                            txid = item.optString("txid"),
                            vout = item.optInt("vout"),
                            scriptHex = hex,
                            amount = item.optDouble("amount", 0.0),
                            height = item.optLong("height"),
                            descriptor = item.optStringOrNull("desc")
                        )
                    )
                }
            }.sortedByDescending { it.amount }
        }
    }
}

/** Result of `scantxoutset` for one or more watch-only descriptors. */
data class ScanResult(
    val success: Boolean,
    val txouts: Long,
    val height: Long,
    val bestBlock: String,
    val totalAmount: Double,
    val utxos: List<ScannedUtxo>
) {
    companion object {
        fun from(json: JSONObject): ScanResult = ScanResult(
            success = json.optBoolean("success", true),
            txouts = json.optLong("txouts"),
            height = json.optLong("height"),
            bestBlock = json.optString("bestblock"),
            totalAmount = json.optDouble("total_amount", json.optDouble("total_unspent_amount", 0.0)),
            utxos = ScannedUtxo.list(json.optJSONArray("unspents"))
        )
    }
}

/** Descriptor description returned by `getdescriptorinfo`. */
data class DescriptorInfo(
    val descriptor: String,
    val isRange: Boolean,
    val isSolvable: Boolean,
    val hasPrivateKeys: Boolean
) {
    companion object {
        fun from(json: JSONObject) = DescriptorInfo(
            descriptor = json.optString("descriptor"),
            isRange = json.optBoolean("isrange"),
            isSolvable = json.optBoolean("issolvable"),
            hasPrivateKeys = json.optBoolean("hasprivatekeys")
        )
    }
}

/** JSON helpers that keep the parsers readable. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key, "")
    return value.ifBlank { null }
}

internal fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

/** The single address of a scriptPubKey, however this Core version reports it. */
internal fun JSONObject.addressText(): String? {
    optStringOrNull("address")?.let { return it }
    val addresses = optJSONArray("addresses")
    if (addresses != null && addresses.length() > 0) return addresses.optString(0, "").ifBlank { null }
    val desc = optStringOrNull("desc")
    if (desc != null && desc.startsWith("addr(")) {
        return desc.removePrefix("addr(").substringBefore(')')
    }
    return null
}
