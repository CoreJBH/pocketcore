package com.corejbh.pocketcore.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explorer parsers against responses captured from a real Bitcoin Core 32.1
 * regtest node (see tools/test-rpc.py).
 */
class ExplorerModelsTest {

    private val block = JSONObject(
        """
        {"hash":"79b0ddf7370fa71897a2cb287d053c9c8a8f22b429a362960a24137a30437e49",
         "confirmations":1,"height":101,"version":536870912,"versionHex":"20000000",
         "merkleroot":"403c8dcd3b3806540dbe69c541b5b3c1695f5d2e4af3f39885331a47fdcb1826",
         "time":1795171918,"mediantime":1795171917,"nonce":0,"bits":"207fffff",
         "target":"7fffff0000","difficulty":4.656542373906925e-10,
         "chainwork":"cc","nTx":2,"previousblockhash":"39c366f241545c7d20cebea34131a62b36f5973c8353f52858d75c0f5293b456",
         "size":428,"strippedsize":248,"weight":1172,
         "tx":[{"txid":"a1","version":1,"size":100,"vsize":100,"weight":400,"locktime":0,
                "vin":[{"txid":"prev","vout":0,"scriptSig":{"hex":""},"is_coinbase":true}],
                "vout":[{"value":50.0,"n":0,"scriptPubKey":{"type":"witness_v0_keyhash","hex":"0014aa","address":"bcrt1qexample"}}]},
               {"txid":"a2","version":1,"size":200,"vsize":110,"weight":440,"locktime":0,
                "vin":[],"vout":[{"value":49.5,"n":0,"scriptPubKey":{"type":"witness_v0_keyhash","hex":"0014bb","address":"bcrt1qother"}}]}]}
        """.trimIndent()
    )

    private val header = JSONObject(
        """
        {"hash":"79b0ddf7370fa71897a2cb287d053c9c8a8f22b429a362960a24137a30437e49",
         "confirmations":1,"height":101,"version":536870912,
         "merkleroot":"403c8dcd3b3806540dbe69c541b5b3c1695f5d2e4af3f39885331a47fdcb1826",
         "time":1795171918,"nonce":0,"bits":"207fffff","difficulty":4.65e-10,"nTx":2,
         "previousblockhash":"39c366f2"}
        """.trimIndent()
    )

    private val transaction = JSONObject(
        """
        {"txid":"cb06c558701dcedc7ddab42c5789fdd32865ef6557ced8d16fd101748a8f160e",
         "version":2,"size":141,"vsize":59,"weight":234,"locktime":0,
         "blockhash":"79b0ddf7","blocktime":1795171918,"confirmations":1,
         "vin":[{"txid":"b968d82b","vout":0,"scriptSig":{"hex":""},"txinwitness":["00"]}],
         "vout":[{"value":49.4999718,"n":0,"scriptPubKey":{"type":"witness_v0_keyhash","hex":"0014cc","address":"bcrt1qdest"}},
                 {"value":0.0000282,"n":1,"scriptPubKey":{"type":"witness_v0_keyhash","hex":"0014dd","address":"bcrt1qchange"}}]}
        """.trimIndent()
    )

    @Test
    fun parsesBlockWithTransactions() {
        val parsed = BlockInfo.fromBlock(block)
        assertEquals(101L, parsed.height)
        assertEquals(2, parsed.txCount)
        assertEquals(2, parsed.transactions.size)
        assertEquals(428L, parsed.size)
        assertEquals(1172L, parsed.weight)
        assertFalse(parsed.headerOnly)
        assertEquals("bcrt1qexample", parsed.transactions[0].outputs[0].address)
        assertEquals("witness_v0_keyhash", parsed.transactions[0].outputs[0].type)
        assertTrue(parsed.transactions[0].isCoinbase)
        assertTrue(parsed.transactions[1].isCoinbase.not())
    }

    @Test
    fun parsesPrunedHeaderOnly() {
        val parsed = BlockInfo.fromHeader(header)
        assertTrue(parsed.headerOnly)
        assertEquals(101L, parsed.height)
        assertEquals(2, parsed.txCount)
        assertNull(parsed.size)
        assertNull(parsed.weight)
        assertTrue(parsed.transactions.isEmpty())
        assertEquals("39c366f2", parsed.previousHash)
    }

    @Test
    fun parsesConfirmedTransaction() {
        val parsed = TxSummary.from(transaction)
        assertEquals(1, parsed.inputCount)
        assertEquals(2, parsed.outputCount)
        assertEquals(1, parsed.outputs[1].index)
        assertEquals(59L, parsed.vsize)
        assertEquals(234L, parsed.weight)
        assertEquals(49.4999718 + 0.0000282, parsed.totalOut, 0.00000001)
        assertFalse(parsed.inMempool)
        assertNull(parsed.fee)
        assertEquals(1795171918L, parsed.blockTime)
    }

    @Test
    fun parsesMempoolTransaction() {
        val json = JSONObject(transaction.toString())
        json.remove("blockhash")
        json.remove("confirmations")
        val parsed = TxSummary.from(json)
        assertTrue(parsed.inMempool)
    }

    @Test
    fun computesFeeWhenPrevoutsAreKnown() {
        val json = JSONObject(transaction.toString())
        json.getJSONArray("vin").getJSONObject(0).put(
            "prevout",
            JSONObject("""{"value":49.5,"scriptPubKey":{"address":"bcrt1qsrc"}}""")
        )
        val parsed = TxSummary.from(json)
        assertEquals(49.5 - 49.4999718 - 0.0000282, parsed.fee!!, 0.00000001)
        assertEquals("bcrt1qsrc", parsed.inputs[0].address)
        assertEquals("bcrt1qdest", parsed.outputs[0].address)
    }

    @Test
    fun parsesScanResultWithScriptPubKeys() {
        val scan = JSONObject(
            """
            {"success":true,"txouts":103,"height":102,"bestblock":"3999",
             "total_amount":5050.5000282,
             "unspents":[
               {"txid":"b3f5","vout":0,"scriptPubKey":"0014751e76e8199196d454941c45d1b3a323f1433bd6",
                "desc":"wpkh([6750c14c/84h/1h/0h/0/0]029aaa)#8s9fl780","amount":50.0,"height":60},
               {"txid":"c7d1","vout":1,"scriptPubKey":"0014e8ac186c199972036ff599634916a44bd0f6ee96",
                "desc":"wpkh([6750c14c/84h/1h/0h/0/1]02bbbb)#aaaa1111","amount":0.5,"height":61}]}
            """.trimIndent()
        )
        val result = ScanResult.from(scan)
        assertEquals(5050.5000282, result.totalAmount, 0.00000001)
        assertEquals(2, result.utxos.size)
        assertEquals("b3f5", result.utxos[0].txid)
        assertEquals(50.0, result.utxos[0].amount, 0.00000001)
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            result.utxos[0].address(BitcoinNetwork.MAINNET)
        )
    }

    @Test
    fun parsesDescriptorInfo() {
        val info = DescriptorInfo.from(
            JSONObject(
                """{"descriptor":"wpkh(xpub/0/*)#f86jzy4u","checksum":"f86jzy4u","isrange":true,
                    "issolvable":true,"hasprivatekeys":false}"""
            )
        )
        assertEquals("wpkh(xpub/0/*)#f86jzy4u", info.descriptor)
        assertTrue(info.isRange)
        assertTrue(info.isSolvable)
        assertFalse(info.hasPrivateKeys)
    }

    @Test
    fun parsesPeerWithServicesAndFlag() {
        val peers = PeerInfo.list(
            JSONArray(
                """
                [{"id":3,"addr":"87.62.101.114:8333","addrBind":"87.62.101.114:8333",
                  "subver":"/Satoshi:31.1.0/","inbound":false,"pingtime":0.144,
                  "bytesrecv":1024,"bytessent":2048,"conntime":1795170000,"version":70016,
                  "services":"0000000000000409",
                  "servicesnames":["NETWORK","WITNESS","NETWORK_LIMITED","P2P_V2"],
                  "banscore":0.0,"permissions":[]}]
                """.trimIndent()
            )
        )
        assertEquals(1, peers.size)
        val peer = peers.first()
        assertEquals(3, peer.nodeId)
        assertEquals("/Satoshi:31.1.0/", peer.userAgent)
        assertEquals("NETWORK \u00b7 WITNESS \u00b7 NETWORK_LIMITED \u00b7 P2P_V2", peer.services)
        assertEquals(144.0, peer.pingMs, 0.001)
        assertEquals("87.62.101.114", peer.host)
        assertFalse(peer.banned)
    }
}
