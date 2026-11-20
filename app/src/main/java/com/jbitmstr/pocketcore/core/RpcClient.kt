package com.jbitmstr.pocketcore.core

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
 * Minimal blocking JSON-RPC client for the local daemon.
 *
 * Only loopback HTTP is used; the app's network security config permits
 * cleartext for 127.0.0.1 alone. Every call opens its own connection, so a long
 * running call (a descriptor scan) never blocks the polling loop.
 */
class RpcClient(
    private val port: Int,
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
            throw RpcException(-1, error.message ?: "Cannot reach bitcoind")
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

    suspend fun peers(): List<PeerInfo> {
        val result = call("getpeerinfo") as? JSONArray ?: return emptyList()
        return PeerInfo.list(result)
    }

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

    suspend fun rawTransaction(txid: String, blockHash: String? = null): JSONObject {
        val params = if (blockHash.isNullOrBlank()) listOf(txid, true) else listOf(txid, true, blockHash)
        return call("getrawtransaction", params, timeoutMs = 30_000).asObject()
    }

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

    private fun Any?.asObject(): JSONObject = this as? JSONObject ?: JSONObject()

    private fun Any?.asString(): String = when (this) {
        is String -> this
        null -> ""
        else -> toString()
    }
}
