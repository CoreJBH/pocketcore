package com.corejbh.pocketcore.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Thrown when bitcoind answers with a JSON-RPC error object. */
class RpcException(val code: Int, message: String) : Exception(message)

/**
 * Error codes the app decides on.
 *
 * The negative values are Bitcoin Core's own (`src/rpc/protocol.h` v32.1);
 * [TRANSPORT] is not: the HTTP exchange itself failed, which is a different
 * fact from "Core answered and refused". An earlier build reported both as -1,
 * so a node that answered RPC_MISC_ERROR was read as unreachable (and the other
 * way round), and the startup warm-up branch could hide a real error. The value
 * sits outside Core's numbering, so no server-side error can collide with it.
 */
object RpcCode {
    /** `RPC_MISC_ERROR`: Core answered, and the answer was an error. */
    const val MISC = -1

    /** `RPC_IN_WARMUP`: Core is still loading and is not ready for this call. */
    const val IN_WARMUP = -28

    /** No HTTP answer: connection refused, reset or timed out. Not a Core code. */
    const val TRANSPORT = -1000
}

/**
 * Parameters of one `getrawtransaction` call.
 *
 * Verbosity 2 - not the boolean `true` - is what carries each input's `prevout`
 * and therefore the input values: without them the input total is unknown and
 * the screen has no fee to show. A block hash given by the caller pins the
 * lookup to that block (versionCode 20).
 */
internal fun rawTransactionParams(txid: String, blockHash: String?): List<Any?> =
    if (blockHash.isNullOrBlank()) {
        listOf(txid, 2)
    } else {
        listOf(txid, 2, blockHash)
    }

/**
 * Minimal blocking JSON-RPC client for the local daemon.
 *
 * Only loopback HTTP is used; the app's network security config permits
 * cleartext for 127.0.0.1 alone. Every call opens its own connection, so a long
 * running call (a descriptor scan, a snapshot dump) never blocks the polling
 * loop.
 */
class RpcClient(
    /** Loopback port this client speaks to; the caller checks who listens on it. */
    val port: Int,
    private val user: String,
    private val password: String
) {

    private val auth: String
        get() = "Basic " + Base64.getEncoder()
            .encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))

    suspend fun call(
        method: String,
        params: List<Any?> = emptyList(),
        timeoutMs: Int = 10_000
    ): Any? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("jsonrpc", "1.0")
            put("id", "pocketcore")
            put("method", method)
            put("params", JSONArray(params))
        }.toString()

        val connection = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", auth)
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val envelope = if (text.isBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(text) }.getOrElse {
                    throw RpcException(code, "Unexpected reply from the process on port $port")
                }
            }
            val error = envelope.optJSONObject("error")
            if (error != null) {
                throw RpcException(error.optInt("code"), error.optString("message", "RPC error"))
            }
            if (code !in 200..299) throw RpcException(code, "HTTP $code from bitcoind")
            envelope.opt("result")
        } catch (error: RpcException) {
            throw error
        } catch (error: IOException) {
            throw RpcException(RpcCode.TRANSPORT, error.message ?: "Cannot reach bitcoind")
        } finally {
            connection.disconnect()
        }
    }

    // --- Node status -------------------------------------------------------

    suspend fun blockchainInfo(): BlockchainInfo =
        BlockchainInfo.from(call("getblockchaininfo").asObject())

    suspend fun networkInfo(): NetworkInfo =
        NetworkInfo.from(call("getnetworkinfo").asObject())

    suspend fun mempoolInfo(): MempoolInfo =
        MempoolInfo.from(call("getmempoolinfo").asObject())

    /**
     * The P2P layer's byte counters.
     *
     * Read once a second by the pulse sampler; the answer is two 64-bit values
     * and the daemon's clock, so this is the cheapest call the app makes.
     */
    suspend fun netTotals(): NetTotals =
        NetTotals.from(call("getnettotals").asObject())

    suspend fun peers(): List<PeerInfo> {
        val result = call("getpeerinfo") as? JSONArray ?: return emptyList()
        return PeerInfo.list(result)
    }

    /**
     * Turns log categories on (debug level) or off, at runtime.
     *
     * The NODE tab's pulse card reads `debug.log` for the P2P messages Core
     * exchanges with peers (`net`) and for the transactions it accepts
     * (`mempool`). Both are off by default in Core 32.1 and both are loud: a
     * synced node with peers writes several lines a second, which no phone
     * should spend on a card nobody is looking at. So the categories are asked
     * for when the card is on screen and dropped when it is not - Core keeps
     * writing for a while after it is asked, and that is the only cost left.
     *
     * The reply is Core's category table; a caller that gets an exception knows
     * the change did not happen (see [NodeController.syncPulseLog]).
     */
    suspend fun setLogCategories(include: List<String>, exclude: List<String>): JSONObject =
        call("logging", listOf(JSONArray(include), JSONArray(exclude))).asObject()

    suspend fun uptime(): Long = (call("uptime") as? Number)?.toLong() ?: 0L

    suspend fun chainStates(): List<ChainStateInfo> =
        ChainStateInfo.list(call("getchainstates").asObject())

    suspend fun stop(): Any? = call("stop", timeoutMs = 20_000)

    /** Used to retire an orphan daemon that still holds the RPC port. */
    suspend fun stopQuick(timeoutMs: Int = 1_500): Boolean = runCatching {
        call("stop", timeoutMs = timeoutMs)
        true
    }.getOrDefault(false)

    // --- AssumeUTXO --------------------------------------------------------

    suspend fun loadTxOutSet(path: String): JSONObject =
        call("loadtxoutset", listOf(path), timeoutMs = 120_000).asObject()

    suspend fun txOutSetInfo(): JSONObject? = runCatching {
        call("gettxoutsetinfo").asObject()
    }.getOrNull()

    // --- Peer actions ------------------------------------------------------

    /** Core 32.1 takes the address first: disconnectnode "host:port". */
    suspend fun disconnectNode(address: String): Any? =
        call("disconnectnode", listOf(address))

    suspend fun setBan(subnet: String, command: String = "add", banTime: Long = 86_400L): Any? =
        call("setban", listOf(subnet, command, banTime))

    // --- Block and transaction explorer -----------------------------------

    suspend fun blockHash(height: Long): String =
        call("getblockhash", listOf(height)).asString()

    suspend fun blockHeader(hash: String): JSONObject =
        call("getblockheader", listOf(hash, true), timeoutMs = 20_000).asObject()

    /** Verbosity 2: the block with every transaction fully expanded. */
    suspend fun block(hash: String): JSONObject =
        call("getblock", listOf(hash, 2), timeoutMs = 60_000).asObject()

    /**
     * A transaction with its inputs expanded (verbosity 2, [rawTransactionParams]).
     */
    suspend fun rawTransaction(txid: String, blockHash: String? = null): JSONObject =
        call("getrawtransaction", rawTransactionParams(txid, blockHash), timeoutMs = 30_000).asObject()

    suspend fun sendRawTransaction(hex: String): String =
        call("sendrawtransaction", listOf(hex), timeoutMs = 30_000).asString()

    // --- Watch-only descriptors -------------------------------------------

    suspend fun descriptorInfo(descriptor: String): DescriptorInfo =
        DescriptorInfo.from(call("getdescriptorinfo", listOf(descriptor)).asObject())

    /**
     * Starts a UTXO scan. The call returns only when the scan finishes, which
     * can take minutes on a phone; the caller polls [scanStatus] meanwhile.
     */
    suspend fun scanTxOutSet(descriptor: String, isRange: Boolean): JSONObject {
        val scanObject = JSONObject().apply {
            put("desc", descriptor)
            if (isRange) put("range", JSONArray().put(0).put(Descriptors.RANGE_END))
        }
        return call(
            "scantxoutset",
            listOf("start", JSONArray().put(scanObject)),
            timeoutMs = 60 * 60 * 1000
        ).asObject()
    }

    /** Progress and best-block information of a scan that is still running. */
    suspend fun scanStatus(): JSONObject =
        call("scantxoutset", listOf("status"), timeoutMs = 10_000).asObject()

    suspend fun scanAbort(): Any? = call("scantxoutset", listOf("abort"), timeoutMs = 10_000)

    // --- UTXO snapshot export ----------------------------------------------

    /**
     * Writes the UTXO set to [path] with `dumptxoutset`.
     *
     * [path] is absolute, so Core does not resolve it against its datadir;
     * [rollbackHeight] null means the current tip (`latest`). Core refuses a
     * file that already exists and writes `<path>.incomplete` while it works, so
     * the caller clears both first and again afterwards.
     *
     * The call returns only when the dump is complete, which on a phone can
     * take minutes, so the read timeout is long rather than the default.
     */
    suspend fun dumpTxOutSet(
        path: String,
        rollbackHeight: Long? = null,
        timeoutMs: Int = DUMP_TIMEOUT_MS
    ): JSONObject = call(
        "dumptxoutset",
        dumpTxOutSetParams(path, rollbackHeight),
        timeoutMs = timeoutMs
    ).asObject()

    private fun Any?.asObject(): JSONObject = this as? JSONObject ?: JSONObject()

    private fun Any?.asString(): String = when (this) {
        is String -> this
        null -> ""
        else -> toString()
    }

    private companion object {
        /**
         * `dumptxoutset` runs for as long as the chainstate walk takes. On a
         * phone that is minutes even for a snapshot of a pruned node, so the
         * read timeout is two hours: a shorter one would fail a dump that is
         * still making progress.
         */
        const val DUMP_TIMEOUT_MS = 2 * 60 * 60 * 1000
    }
}
