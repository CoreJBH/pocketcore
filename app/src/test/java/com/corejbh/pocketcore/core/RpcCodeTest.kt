package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error codes `NodeController` decides on.
 *
 * Before versionCode 13 a failed HTTP exchange was reported as
 * `RPC_MISC_ERROR` (-1), so "Core answered with an error" and "nothing answered"
 * were the same value, and the startup branch that treats -1 as a warm-up could
 * hide either one. -28 (warm-up) had no branch at all.
 */
class RpcCodeTest {

    @Test
    fun coreCodesKeepBitcoinCoresNumbering() {
        // src/rpc/protocol.h (v32.1): RPC_MISC_ERROR = -1, RPC_IN_WARMUP = -28.
        assertEquals(-1, RpcCode.MISC)
        assertEquals(-28, RpcCode.IN_WARMUP)
    }

    @Test
    fun aFailedConnectionIsNotACoreError() {
        assertNotEquals(RpcCode.MISC, RpcCode.TRANSPORT)
        assertNotEquals(RpcCode.IN_WARMUP, RpcCode.TRANSPORT)
    }

    @Test
    fun theTransportCodeStaysOutsideCoreErrorRange() {
        // Core's negative codes this app can receive run to -32; the transport
        // code sits below that, so no JSON-RPC error object can be mistaken for
        // an unreachable node (or the other way round).
        assertTrue(RpcCode.TRANSPORT < -32)
    }

    @Test
    fun anExceptionCarriesItsCode() {
        val error = RpcException(RpcCode.TRANSPORT, "Connection refused")
        assertEquals(RpcCode.TRANSPORT, error.code)
        assertEquals("Connection refused", error.message)
    }
}
