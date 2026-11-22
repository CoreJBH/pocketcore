package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One policy pass per connectivity change, not per callback.
 *
 * `onCapabilitiesChanged` fires for signal strength and bandwidth as well as for
 * a transport coming or going. versionCode 19 coalesces the ones that repeat the
 * state the node policy already saw; a real change still passes immediately.
 */
class EnvironmentGateTest {

    private var now = 1_000_000L
    private val gate = EnvironmentGate(windowMillis = 2_000L) { now }

    private val onWifi = NetworkSignature.of(
        wifi = true, cellular = true, ethernet = false, vpn = false, validated = true
    )
    private val onCellular = NetworkSignature.of(
        wifi = false, cellular = true, ethernet = false, vpn = false, validated = true
    )

    @Test
    fun theFirstSignalPasses() {
        assertTrue(gate.shouldEvaluate(onWifi))
    }

    @Test
    fun aRepeatedSignalInsideTheWindowIsDropped() {
        assertTrue(gate.shouldEvaluate(onWifi))
        now += 150
        assertFalse("a signal-strength callback is not a policy change", gate.shouldEvaluate(onWifi))
        now += 150
        assertFalse(gate.shouldEvaluate(onWifi))
    }

    @Test
    fun aRealChangePassesAtOnce() {
        assertTrue(gate.shouldEvaluate(onWifi))
        now += 100
        assertTrue("Wi-Fi gone has to pause the node now", gate.shouldEvaluate(onCellular))
        now += 100
        assertTrue(gate.shouldEvaluate(onWifi))
    }

    @Test
    fun aRepeatAfterTheWindowPassesAgain() {
        assertTrue(gate.shouldEvaluate(onWifi))
        now += EnvironmentGate.WINDOW_MS
        assertTrue("a state that drifted is re-read eventually", gate.shouldEvaluate(onWifi))
    }

    @Test
    fun aBurstOfCallbacksCostsOnePass() {
        var passes = 0
        repeat(25) {
            if (gate.shouldEvaluate(onWifi)) passes++
            now += 40
        }
        // 25 callbacks over one second: the window admits the first only.
        assertEquals(1, passes)
    }

    /**
     * What the signature covers: the transports and validation the label and the
     * Wi-Fi gate read. Two capability callbacks that differ only in strength or
     * bandwidth produce the same string here, so the gate drops the second.
     */
    @Test
    fun theSignatureCoversOnlyWhatThePolicyReads() {
        assertEquals(
            NetworkSignature.of(wifi = true, cellular = false, ethernet = false, vpn = false, validated = true),
            NetworkSignature.of(wifi = true, cellular = false, ethernet = false, vpn = false, validated = true)
        )
        assertNotEquals(
            NetworkSignature.of(wifi = true, cellular = false, ethernet = false, vpn = false, validated = true),
            NetworkSignature.of(wifi = false, cellular = true, ethernet = false, vpn = false, validated = true)
        )
    }
}
