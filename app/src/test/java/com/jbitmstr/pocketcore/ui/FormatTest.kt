package com.jbitmstr.pocketcore.ui

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
}
