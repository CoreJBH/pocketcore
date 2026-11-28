package com.corejbh.pocketcore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun formatsBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("293 B", formatBytes(293))
        assertEquals("550 MB", formatBytes(576716800))
        assertEquals("1.2 GB", formatBytes(1_288_490_188))
    }

    @Test
    fun formatsPercent() {
        assertEquals("50.00%", formatPercent(0.5))
        assertEquals("100.00%", formatPercent(0.999999))
        assertEquals("0.00%", formatPercent(0.0))
    }

    @Test
    fun formatsCounts() {
        assertEquals("870,000", formatCount(870000))
    }

    @Test
    fun formatsDurations() {
        assertEquals("2m 5s", formatDuration(125))
        assertEquals("3h 0m", formatDuration(3 * 3600))
        assertEquals("1d 2h", formatDuration(26 * 3600))
    }

    @Test
    fun formatsFeeRate() {
        assertEquals("1 sat/vB", formatFeeRate(0.00001))
    }

    @Test
    fun formatsBtcFromExactSatoshi() {
        assertEquals("49.5", formatBtc(4_950_000_000L, withUnit = false))
        assertEquals("49.4999718", formatBtc(4_949_997_180L, withUnit = false))
        assertEquals("0.00000001", formatBtc(1L, withUnit = false))
        assertEquals("0.0", formatBtc(0L, withUnit = false))
        assertEquals("1.0 BTC", formatBtc(100_000_000L))
        // An amount a Double cannot hold exactly is still shown exactly.
        assertEquals("0.1", formatBtc(10_000_000L, withUnit = false))
        assertEquals("0.3", formatBtc(30_000_000L, withUnit = false))
    }

    @Test
    fun formatsSatoshiFromTheInteger() {
        assertEquals("0 sat", formatSats(0L))
        assertEquals("2,820 sat", formatSats(2_820L))
        // The whole supply, 21 million BTC, in satoshis.
        assertEquals("2,100,000,000,000,000 sat", formatSats(2_100_000_000_000_000L))
    }

    @Test
    fun formatsPulseRates() {
        assertEquals("15.3 KB/s", formatRate(15_667.0))
        assertEquals("0.0 KB/s", formatRate(0.0))
        assertEquals("1.5 MB/s", formatRate(1_572_864.0))
        // A value that is not a measurement reads as no traffic, never NaN.
        assertEquals("0.0 KB/s", formatRate(Double.NaN))
        assertEquals("4.2K", formatRateShort(4_301.0))
        assertEquals("0.0K", formatRateShort(0.0))
        assertEquals("1.3M", formatRateShort(1_363_149.0))
        assertEquals("2.4", formatTxRate(2.4))
        assertEquals("0.0", formatTxRate(Double.POSITIVE_INFINITY))
    }
}
