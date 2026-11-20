package com.jbitmstr.pocketcore.core

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
                transactions = TxSummary.list(transactions)
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

/** One input of a transaction, resolved as far as the node can resolve it. */
data class TxInput(
    val prevTxid: String,
    val previousOutput: Int,
    val address: String?,
    val value: Double?,
    val coinbase: Boolean
) {
    companion object {
        fun from(json: JSONObject): TxInput {
            val coinbase = json.has("coinbase") || json.optBoolean("is_coinbase", false)
            val prevout = json.optJSONObject("prevout")
            val script = prevout?.optJSONObject("scriptPubKey")
            return TxInput(
                prevTxid = json.optString("txid", if (coinbase) "Coinbase" else ""),
                previousOutput = json.optInt("vout", -1),
                address = script?.addressText(),
                value = prevout?.optDoubleOrNull("value"),
                coinbase = coinbase
            )
        }
    }
}

/** One output of a transaction. */
data class TxOutput(
    val index: Int,
    val address: String?,
    val value: Double,
    val type: String?,
    val scriptHex: String?
) {
    companion object {
        fun from(json: JSONObject): TxOutput {
            val script = json.optJSONObject("scriptPubKey")
            return TxOutput(
                index = json.optInt("n"),
                address = script?.addressText(),
                value = json.optDouble("value", 0.0),
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
    val totalOut: Double,
    val fee: Double?,
    val isCoinbase: Boolean
) {
    val inputCount: Int get() = inputs.size
    val outputCount: Int get() = outputs.size

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
            val totalOut = outputs.sumOf { it.value }
            val fee = if (inputs.isNotEmpty() && inputs.all { it.value != null && !it.coinbase }) {
                inputs.sumOf { it.value ?: 0.0 } - totalOut
            } else {
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
                totalOut = totalOut,
                fee = fee,
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

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
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
