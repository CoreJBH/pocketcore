package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cleaning and validation of a pasted signed transaction. */
class RawTransactionTest {

    private val hex =
        "0200000001b3f547e6f74616a7fe066b4b917ed4fa58017cc0fd1d1dadff845c7c3a5e790101000000" +
            "00ffffffff0100e1f505000000001600141588e97c0335077df13db3813d25fc05e255322800000000"

    @Test
    fun keepsPlainHex() {
        assertEquals(hex, RawTransaction.clean(hex))
        assertEquals(RawTransaction.Parsed.Valid(hex), RawTransaction.parse(hex))
    }

    @Test
    fun stripsWhitespaceAndPrefixes() {
        val wrapped = "  0x" + hex.chunked(16).joinToString("\n\t ") + "  "
        assertEquals(hex, RawTransaction.clean(wrapped))
        assertEquals(RawTransaction.Parsed.Valid(hex), RawTransaction.parse(wrapped))
        assertEquals(RawTransaction.Parsed.Valid(hex), RawTransaction.parse("\"$hex\""))
    }

    @Test
    fun rejectsNonHex() {
        val parsed = RawTransaction.parse("0200zz")
        assertTrue(parsed is RawTransaction.Parsed.Invalid)
        assertTrue((parsed as RawTransaction.Parsed.Invalid).reason.contains("not hexadecimal"))
    }

    @Test
    fun rejectsOddLengthAndShortInput() {
        assertTrue(RawTransaction.parse("0200000") is RawTransaction.Parsed.Invalid)
        assertTrue(RawTransaction.parse("0200") is RawTransaction.Parsed.Invalid)
        assertTrue(RawTransaction.parse("") is RawTransaction.Parsed.Invalid)
    }

    @Test
    fun reportsByteLength() {
        assertEquals(82, RawTransaction.byteLength(hex))
        assertEquals(82, RawTransaction.byteLength("0x" + hex.chunked(4).joinToString(" ")))
    }

    @Test
    fun isHexUnderstandsCase() {
        assertTrue(RawTransaction.isHex("00aAbB"))
        assertTrue(!RawTransaction.isHex("00aAbBz"))
        assertTrue(!RawTransaction.isHex(""))
    }
}
