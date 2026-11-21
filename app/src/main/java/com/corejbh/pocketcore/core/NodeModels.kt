package com.corejbh.pocketcore.core

import org.json.JSONArray
import org.json.JSONObject

/** A block of values coming from getblockchaininfo. */
data class BlockchainInfo(
    val chain: String,
    val blocks: Long,
    val headers: Long,
    val verificationProgress: Double,
    val initialBlockDownload: Boolean,
    val sizeOnDisk: Long,
    val pruned: Boolean,
    val pruneHeight: Long,
    val pruneTargetSize: Long,
    val bestBlockHash: String,
    val difficulty: Double,
    val time: Long
) {
    companion object {
        fun from(json: JSONObject) = BlockchainInfo(
            chain = json.optString("chain", "main"),
            blocks = json.optLong("blocks"),
            headers = json.optLong("headers"),
            verificationProgress = json.optDouble("verificationprogress", 0.0),
            initialBlockDownload = json.optBoolean("initialblockdownload", true),
            sizeOnDisk = json.optLong("size_on_disk"),
            pruned = json.optBoolean("pruned"),
            pruneHeight = json.optLong("pruneheight"),
            pruneTargetSize = json.optLong("prune_target_size"),
            bestBlockHash = json.optString("bestblockhash", ""),
            difficulty = json.optDouble("difficulty", 0.0),
            time = json.optLong("time")
        )
    }
}

/** A block of values coming from getnetworkinfo. */
data class NetworkInfo(
    val version: Long,
    val subversion: String,
    val protocolVersion: Long,
    val connections: Int,
    val connectionsIn: Int,
    val connectionsOut: Int,
    val networkActive: Boolean,
    val relayFee: Double,
    val timeOffset: Long
) {
    companion object {
        fun from(json: JSONObject) = NetworkInfo(
            version = json.optLong("version"),
            subversion = json.optString("subversion", ""),
            protocolVersion = json.optLong("protocolversion"),
            connections = json.optInt("connections"),
            connectionsIn = json.optInt("connections_in"),
            connectionsOut = json.optInt("connections_out"),
            networkActive = json.optBoolean("networkactive", true),
            relayFee = json.optDouble("relayfee", 0.0),
            timeOffset = json.optLong("timeoffset")
        )
    }
}

/** A block of values coming from getmempoolinfo. */
data class MempoolInfo(
    val transactions: Int,
    val bytes: Long,
    val usage: Long,
    val minFee: Double
) {
    companion object {
        fun from(json: JSONObject) = MempoolInfo(
            transactions = json.optInt("size"),
            bytes = json.optLong("bytes"),
            usage = json.optLong("usage"),
            minFee = json.optDouble("mempoolminfee", 0.0)
        )
    }
}

/** One connected peer from getpeerinfo. */
data class PeerInfo(
    val nodeId: Int,
    val address: String,
    val userAgent: String,
    val inbound: Boolean,
    val pingMs: Double,
    val bytesReceived: Long,
    val bytesSent: Long,
    val connectionSeconds: Long,
    val services: String,
    val version: Long,
    val banned: Boolean,
    val permissions: String
) {
    /** "127.0.0.1:8333" without the port, used for setban. */
    val host: String get() = when {
        address.startsWith("[") -> address.substringBefore("]").removePrefix("[")
        else -> address.substringBeforeLast(':', address)
    }

    val country: String? get() = GeoIp.countryOf(address)
    val flag: String get() = Countries.flag(country)
    val countryName: String get() = Countries.name(country)

    companion object {
        fun list(array: JSONArray): List<PeerInfo> = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val services = buildList {
                    val names = item.optJSONArray("servicesnames")
                    if (names != null) {
                        for (position in 0 until names.length()) {
                            names.optString(position, "").takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                    if (isEmpty()) {
                        val raw = item.optString("services", "")
                        if (raw.isNotBlank()) add("services 0x$raw")
                    }
                }
                add(
                    PeerInfo(
                        nodeId = item.optInt("id", -1),
                        address = item.optString("addr", ""),
                        userAgent = item.optString("subver", ""),
                        inbound = item.optBoolean("inbound"),
                        pingMs = item.optDouble("pingtime", 0.0) * 1000.0,
                        bytesReceived = item.optLong("bytesrecv"),
                        bytesSent = item.optLong("bytessent"),
                        connectionSeconds = item.optLong("conntime").let { started ->
                            if (started <= 0) 0 else (System.currentTimeMillis() / 1000 - started)
                        },
                        services = services.joinToString(" \u00b7 "),
                        version = item.optLong("version"),
                        banned = item.optDouble("banscore", 0.0) > 0.0,
                        permissions = item.optJSONArray("permissions")?.let { list ->
                            buildList {
                                for (position in 0 until list.length()) {
                                    list.optString(position, "").takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                            }.joinToString(" ")
                        }.orEmpty()
                    )
                )
            }
        }
    }
}

/** One entry of getchainstates (validation chainstate or AssumeUTXO snapshot). */
data class ChainStateInfo(
    val name: String,
    val blocks: Long,
    val verificationProgress: Double,
    val snapshot: Boolean,
    val validated: Boolean,
    val baseHash: String
) {
    companion object {
        fun list(json: JSONObject): List<ChainStateInfo> {
            val array = json.optJSONArray("chainstates") ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val baseHash = item.optString("snapshot_blockhash", "")
                    val snapshot = baseHash.isNotEmpty()
                    val validated = item.optBoolean("validated", false)
                    add(
                        ChainStateInfo(
                            name = when {
                                snapshot && validated -> "Snapshot chainstate (validated)"
                                snapshot -> "Snapshot chainstate"
                                else -> "Validated chainstate"
                            },
                            blocks = item.optLong("blocks"),
                            verificationProgress = item.optDouble("verificationprogress", 0.0),
                            snapshot = snapshot,
                            validated = validated,
                            baseHash = baseHash
                        )
                    )
                }
            }
        }
    }
}
