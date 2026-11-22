package com.corejbh.pocketcore.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exact satoshi parsing of [BtcAmount].
 *
 * The explorer adds amounts up to get the outputs' total and the fee, so every
 * amount is read as an integer number of satoshis instead of a `Double`.
 */
class BtcAmountTest {

    @Test
    fun parsesCoreAmountsExactly() {
        assertEquals(0L, BtcAmount.parse("0")!!)
        assertEquals(1L, BtcAmount.parse("0.00000001")!!)
        assertEquals(4_949_997_180L, BtcAmount.parse("49.4999718")!!)
        assertEquals(2_820L, BtcAmount.parse("0.00002820")!!)
        assertEquals(100_000_000L, BtcAmount.parse("1.0")!!)
        assertEquals(2_100_000_000_000_000L, BtcAmount.parse("21000000")!!)
        // Core's JSON may print a small number in exponent form; the value is the
        // same one satoshi.
        assertEquals(1L, BtcAmount.parse("1E-8")!!)
    }

    @Test
    fun aMissingOrNonNumericAmountIsNull() {
        assertNull(BtcAmount.parse(null))
        assertNull(BtcAmount.parse(""))
        assertNull(BtcAmount.parse("   "))
        assertNull(BtcAmount.parse("not a number"))
    }

    @Test
    fun aFractionOfASatoshiRoundsToTheNearestOne() {
        // Core never reports a fraction of a satoshi; if anything does, the
        // nearest satoshi is shown rather than dropping the amount.
        assertEquals(2L, BtcAmount.parse("0.000000019")!!)
        assertEquals(1L, BtcAmount.parse("0.000000014")!!)
    }

    @Test
    fun readsTheAmountsOwnDecimalText() {
        val json = JSONObject("""{"value":49.4999718,"zero":0,"text":"0.1","none":null}""")
        assertEquals(4_949_997_180L, BtcAmount.fromJson(json, "value"))
        assertEquals(0L, BtcAmount.fromJson(json, "zero"))
        assertEquals(10_000_000L, BtcAmount.fromJson(json, "text"))
        assertNull(BtcAmount.fromJson(json, "none"))
        assertNull(BtcAmount.fromJson(json, "missing"))
    }

    @Test
    fun convertingBackToBtcDoesNotMoveTheAmount() {
        assertEquals(49.5, BtcAmount.toBtc(4_950_000_000L), 0.0)
        assertEquals(0.00000001, BtcAmount.toBtc(1L), 0.0)
        assertEquals(0.0, BtcAmount.toBtc(0L), 0.0)
    }
}
