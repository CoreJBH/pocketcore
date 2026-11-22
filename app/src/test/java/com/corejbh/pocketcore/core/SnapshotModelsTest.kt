package com.corejbh.pocketcore.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumptxoutset` request and reply, against what Bitcoin Core 32.1 actually
 * answered on regtest (tools/test-rpc.py runs the same RPCs there):
 *
 * ```
 * dumptxoutset utxo.dat latest
 * {"coins_written":120,"base_hash":"6613ebd8...","base_height":120,
 *  "path":"/workspace/tmp/pcexp/regtest/utxo.dat",
 *  "txoutset_hash":"53cc8681...","nchaintx":121}
 * ```
 */
class SnapshotModelsTest {

    private val reply = JSONObject(
        """
        {"coins_written":120,
         "base_hash":"6613ebd8fea159d7053146fc76e808e01d4e3095c243ac62f8d8c90fddb2f9da",
         "base_height":120,
         "path":"/data/user/0/com.corejbh.pocketcore/cache/utxo-main-1763000000000.dat",
         "txoutset_hash":"53cc868131bd975220cd51cbd4c9a5a522ddc802444d67a3ec5a04ef771fc405",
         "nchaintx":121}
        """.trimIndent()
    )

    @Test
    fun parsesTheReplyCoreReturns() {
        val dump = TxOutSetDump.from(reply)
        assertEquals(120L, dump.baseHeight)
        assertEquals(120L, dump.coinsWritten)
        assertEquals(121L, dump.chainTx)
        assertEquals("53cc868131bd975220cd51cbd4c9a5a522ddc802444d67a3ec5a04ef771fc405", dump.txoutsetHash)
        assertTrue(dump.baseHash.startsWith("6613ebd8"))
        assertTrue(dump.path.endsWith(".dat"))
    }

    @Test
    fun summaryCarriesTheThreeFactsToCompare() {
        val text = TxOutSetDump.from(reply).summary()
        assertTrue(text.contains("block 120"))
        assertTrue(text.contains("120 coins"))
        // The hash is abbreviated the same way the rest of the app shows one.
        assertTrue(text.contains("53cc868131...771fc405"))
    }

    @Test
    fun oneCoinIsNotOneCoins() {
        val single = JSONObject(reply.toString()).put("coins_written", 1)
        assertTrue(TxOutSetDump.from(single).summary().contains("1 coin,"))
    }

    @Test
    fun latestIsAskedForExplicitly() {
        // Core 32.1 fails with -8 "Invalid snapshot type \"\"" when the type is
        // left out and no rollback option is given, so it is always written.
        val params = dumpTxOutSetParams("/cache/utxo.dat")
        assertEquals(2, params.size)
        assertEquals("/cache/utxo.dat", params[0])
        assertEquals("latest", params[1])
    }

    @Test
    fun aRollbackHeightTravelsInTheNamedOptions() {
        val params = dumpTxOutSetParams("/cache/utxo.dat", 853_456L)
        assertEquals(3, params.size)
        assertEquals("/cache/utxo.dat", params[0])
        assertEquals("", params[1])
        assertEquals(853_456L, (params[2] as JSONObject).getLong("rollback"))
    }
}
