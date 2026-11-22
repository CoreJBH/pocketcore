package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ending an orphaned daemon of this app with the stop RPC instead of a kill.
 *
 * On Android 10+ the socket table that used to prove the listener is unreadable,
 * so the previous build killed every orphan and risked a chainstate that needs a
 * reindex. The replacement only sends the stop when three facts about this app's
 * own daemon hold ([OrphanStop.maySend]); everything else is still a refusal.
 */
class OrphanStopTest {

    private val stored = RpcCredential("rpcuser", "storedpassword")

    @Test
    fun theStoredPairIsUsedWhenThereIsNoCookie() {
        assertEquals(listOf(stored), OrphanStop.credentials(null, "rpcuser", "storedpassword"))
    }

    @Test
    fun theCookieComesFirst() {
        val pairs = OrphanStop.credentials("__cookie__:deadbeef", "rpcuser", "storedpassword")
        assertEquals(RpcCredential("__cookie__", "deadbeef"), pairs.first())
        assertEquals(stored, pairs.last())
        assertEquals(2, pairs.size)
    }

    @Test
    fun aCookieEqualToTheStoredPairIsTriedOnce() {
        val pairs = OrphanStop.credentials("rpcuser:storedpassword", "rpcuser", "storedpassword")
        assertEquals(listOf(stored), pairs)
    }

    @Test
    fun blankCredentialsAreNotACredential() {
        assertEquals(emptyList<RpcCredential>(), OrphanStop.credentials(null, "", "storedpassword"))
        assertEquals(emptyList<RpcCredential>(), OrphanStop.credentials(null, "rpcuser", ""))
    }

    @Test
    fun aMalformedCookieIsIgnored() {
        assertNull(OrphanStop.fromCookie(null))
        assertNull(OrphanStop.fromCookie(""))
        assertNull(OrphanStop.fromCookie("no-colon-here"))
        assertNull(OrphanStop.fromCookie(":password"))
        assertNull(OrphanStop.fromCookie("user:"))
        assertNull(OrphanStop.fromCookie("a".repeat(600) + ":password"))
    }

    @Test
    fun aPasswordMayContainAColon() {
        assertEquals(
            RpcCredential("user", "pass:word"),
            OrphanStop.fromCookie("user:pass:word\n")
        )
    }

    @Test
    fun theStopNeedsALiveDaemonOfOurs() {
        // Nothing of this app runs: there is nothing to stop, and the port is
        // somebody else's business.
        assertFalse(OrphanStop.maySend(emptyList(), 8332, 8332, listOf(stored), bindConfirmed = true))
    }

    @Test
    fun theStopNeedsThePortRecordedInOurOwnConf() {
        // The conf is the only endpoint a daemon of ours can be listening on. A
        // different port is a listener this app never configured.
        assertFalse(OrphanStop.maySend(listOf(4242), 8332, 8399, listOf(stored), bindConfirmed = true))
        assertFalse(OrphanStop.maySend(listOf(4242), 8332, null, listOf(stored), bindConfirmed = true))
        assertTrue(OrphanStop.maySend(listOf(4242), 8332, 8332, listOf(stored), bindConfirmed = true))
    }

    @Test
    fun theStopNeedsACredential() {
        assertFalse(OrphanStop.maySend(listOf(4242), 8332, 8332, emptyList(), bindConfirmed = true))
    }

    @Test
    fun aPortOutsideTheRangeIsRefused() {
        assertFalse(OrphanStop.maySend(listOf(4242), 0, 0, listOf(stored), bindConfirmed = true))
        assertFalse(OrphanStop.maySend(listOf(4242), 70_000, 70_000, listOf(stored), bindConfirmed = true))
    }

    /**
     * The eighth review's finding: on Android 10+ the three facts above can all
     * hold while the port belongs to another app, because nothing on these
     * versions can prove the listener. Without a bind line in the daemon's own log
     * the password stays in this process and the daemon is ended with SIGTERM.
     */
    @Test
    fun theStopNeedsTheDaemonsOwnBindLine() {
        assertFalse(OrphanStop.maySend(listOf(4242), 8332, 8332, listOf(stored), bindConfirmed = false))
        assertTrue(OrphanStop.maySend(listOf(4242), 8332, 8332, listOf(stored), bindConfirmed = true))
    }
}
