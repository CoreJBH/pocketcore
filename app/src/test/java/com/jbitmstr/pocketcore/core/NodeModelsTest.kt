package com.jbitmstr.pocketcore.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests against real bitcoind 32.1 responses captured from a signet
 * node started with the configuration this app generates.
 */
class NodeModelsTest {

    private val blockchainInfo = """
        {"chain":"signet","blocks":0,"headers":0,
         "bestblockhash":"00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6",
         "bits":"1e0377ae","difficulty":0.001126515290698186,"time":1598918400,
         "verificationprogress":2.877695656596751e-08,"initialblockdownload":true,
         "size_on_disk":293,"pruned":true,"pruneheight":0,"automatic_pruning":true,
         "prune_target_size":576716800,"warnings":[]}
    """.trimIndent()

    @Test
    fun parsesBlockchainInfo() {
        val info = BlockchainInfo.from(JSONObject(blockchainInfo))
        assertEquals("signet", info.chain)
        assertEquals(0L, info.blocks)
        assertTrue(info.initialBlockDownload)
        assertTrue(info.pruned)
        assertEquals(576716800L, info.pruneTargetSize)
        assertEquals(293L, info.sizeOnDisk)
        assertTrue(info.verificationProgress < 0.001)
    }

    @Test
    fun parsesNetworkInfo() {
        val json = JSONObject(
            """{"version":320100,"subversion":"/Satoshi:32.1.0(POCKETCORE)/",
                "protocolversion":70017,"networkactive":true,"connections":3,
                "connections_in":1,"connections_out":2,"relayfee":0.00001,"timeoffset":0}"""
        )
        val info = NetworkInfo.from(json)
        assertEquals(320100L, info.version)
        assertEquals("/Satoshi:32.1.0(POCKETCORE)/", info.subversion)
        assertEquals(3, info.connections)
        assertEquals(1, info.connectionsIn)
        assertEquals(2, info.connectionsOut)
    }

    @Test
    fun parsesMempoolInfo() {
        val json = JSONObject(
            """{"loaded":true,"size":42,"bytes":10500,"usage":22000,
                "maxmempool":50000000,"mempoolminfee":0.00000100}"""
        )
        val info = MempoolInfo.from(json)
        assertEquals(42, info.transactions)
        assertEquals(10500L, info.bytes)
        assertEquals(22000L, info.usage)
    }

    @Test
    fun parsesPeers() {
        val array = JSONArray(
            """[{"addr":"203.0.113.7:38333","subver":"/Satoshi:27.0.0/","inbound":false,
                 "pingtime":0.042,"bytesrecv":1500,"bytessent":900,"conntime":1000000000},
                {"addr":"198.51.100.9:38333","subver":"/Satoshi:32.1.0/","inbound":true,
                 "pingtime":0.31,"bytesrecv":10,"bytessent":20,"conntime":1700000000}]"""
        )
        val peers = PeerInfo.list(array)
        assertEquals(2, peers.size)
        assertFalse(peers[0].inbound)
        assertTrue(peers[1].inbound)
        assertEquals(42.0, peers[0].pingMs, 0.5)
    }

    @Test
    fun parsesSingleValidatedChainstate() {
        val json = JSONObject(
            """{"headers":0,"chainstates":[{"blocks":0,
                "bestblockhash":"00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6",
                "verificationprogress":2.8e-08,"coins_db_cache_bytes":8388608,
                "coins_tip_cache_bytes":123731968,"validated":true}]}"""
        )
        val states = ChainStateInfo.list(json)
        assertEquals(1, states.size)
        assertFalse(states[0].snapshot)
        assertTrue(states[0].validated)
        assertEquals("Validated chainstate", states[0].name)
    }

    @Test
    fun parsesSnapshotAndValidationChainstates() {
        val json = JSONObject(
            """{"headers":870001,"chainstates":[
                 {"blocks":840000,"bestblockhash":"0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5",
                  "verificationprogress":1.0,"validated":true},
                 {"blocks":870000,"bestblockhash":"00000000000000000001b2a5a3f5b0e8b8f6d0d1d0d8c9b8b8a7a6a5a4a3",
                  "verificationprogress":0.9999,"snapshot_blockhash":"000000000000000000025a5c5a5a5c5a5c5a5a5c5a5c5a5c5a5a5c5a5c5a5c5a",
                  "validated":false}]}"""
        )
        val states = ChainStateInfo.list(json)
        assertEquals(2, states.size)
        assertFalse(states[0].snapshot)
        assertEquals(840000L, states[0].blocks)
        assertTrue(states[1].snapshot)
        assertEquals("Snapshot chainstate", states[1].name)
        assertEquals(870000L, states[1].blocks)
        assertTrue(states[1].baseHash.startsWith("000000000000000000025a5c"))
    }

    @Test
    fun parsesLoadTxOutSetResult() {
        val result = JSONObject(
            """{"coins_loaded":79000000,"tip_hash":"0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5",
                "base_height":840000,"path":"/data/utxo.dat"}"""
        )
        assertEquals(840000L, result.optLong("base_height"))
        assertEquals(79000000L, result.optLong("coins_loaded"))
    }

    @Test
    fun settingsGenerateStableCredentials() {
        val first = NodeSettings.generateToken(32)
        val second = NodeSettings.generateToken(32)
        assertEquals(32, first.length)
        assertEquals(32, second.length)
        assertFalse(first == second)
        assertTrue(first.all { it.isLetterOrDigit() })
    }
}
