package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HeaderSyncTest {

    @Test
    fun readsThePreSyncLineCoreWrites() {
        val log = """
            2026-11-20T10:42:50Z Loading block index...
            2026-11-20T10:42:51Z Pre-synchronizing blockheaders, height: 812345 (~42.10%)
        """.trimIndent()
        val sync = HeaderSync.latest(log)
        assertEquals(812345L, sync?.height)
        assertEquals(42.10, sync?.percent ?: 0.0, 0.0001)
        assertTrue(sync?.presync == true)
    }

    @Test
    fun takesTheNewestLine() {
        val log = """
            Pre-synchronizing blockheaders, height: 100 (~0.01%)
            Pre-synchronizing blockheaders, height: 200000 (~12.50%)
            Pre-synchronizing blockheaders, height: 812345 (~42.10%)
        """.trimIndent()
        val sync = HeaderSync.latest(log)
        assertEquals(812345L, sync?.height)
        assertEquals(0.4210, sync?.fraction ?: 0.0, 0.0001)
    }

    @Test
    fun committedHeaderChainIsNotAPreSync() {
        val sync = HeaderSync.latest("Synchronizing blockheaders, height: 976000 (~99.99%)")
        assertEquals(976000L, sync?.height)
        assertFalse(sync?.presync ?: true)
    }

    @Test
    fun logWithoutHeaderLinesHasNoProgress() {
        val log = """
            2026-11-20T10:42:50Z Loading block index...
            2026-11-20T10:42:51Z opencon thread start
            2026-11-20T10:42:52Z AddLocal(127.0.0.1:8334,4)
        """.trimIndent()
        assertNull(HeaderSync.latest(log))
    }

    @Test
    fun unrelatedLinesDoNotMatch() {
        assertNull(HeaderSync.latest("Pre-synchronizing blockheaders, height: nope (~4x%)"))
        assertNull(HeaderSync.latest("Synchronized blockheaders"))
    }

    @Test
    fun percentIsClampedToTheBarRange() {
        val below = HeaderSync.latest("Pre-synchronizing blockheaders, height: 0 (~0.00%)")
        assertEquals(0.0, below?.fraction ?: -1.0, 0.0001)

        val above = HeaderSync(height = 1, percent = 140.0, presync = true)
        assertEquals(1.0, above.fraction, 0.0001)
    }

    @Test
    fun windowsLineEndingsAreRead() {
        val log = "Pre-synchronizing blockheaders, height: 512000 (~25.00%)\r\n" +
            "Pre-synchronizing blockheaders, height: 640000 (~31.25%)\r\n"
        val sync = HeaderSync.latest(log)
        assertEquals(640000L, sync?.height)
        assertEquals(31.25, sync?.percent ?: 0.0, 0.0001)
    }
}
