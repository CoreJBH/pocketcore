package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parameters of `getrawtransaction`.
 *
 * Verbosity 2, not the boolean `true`: only a fully expanded transaction carries
 * each input's `prevout`, and without it the screen has no input values and no
 * fee (versionCode 20).
 */
class TransactionLookupTest {

    @Test
    fun asksForAVerboseTransaction() {
        assertEquals(listOf<Any?>("txid", 2), rawTransactionParams("txid", null))
        assertEquals(listOf<Any?>("txid", 2), rawTransactionParams("txid", ""))
    }

    @Test
    fun pinsTheLookupToABlockWhenOneIsGiven() {
        assertEquals(listOf<Any?>("txid", 2, "block"), rawTransactionParams("txid", "block"))
    }
}
