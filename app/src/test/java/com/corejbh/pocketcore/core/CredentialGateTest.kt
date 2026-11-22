package com.corejbh.pocketcore.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides when the RPC credentials may leave the app.
 *
 * versionCode 13 turned it fail-closed after a review finding: an unreadable
 * /proc entry used to be read as "probably our daemon", which made an
 * unverifiable owner of the port the one case that still received the password.
 * versionCode 14 closed the same window at the other end: no client is handed
 * out at all before this start's listener is verified.
 */
class CredentialGateTest {

    @Test
    fun onlyTheDaemonsOwnSocketReceivesTheCredentials() {
        assertTrue(credentialsAllowed(SocketOwner.OURS))
    }

    @Test
    fun aForeignListenerIsRefused() {
        assertFalse(credentialsAllowed(SocketOwner.FOREIGN))
    }

    @Test
    fun anUnprovenOwnerIsRefused() {
        // The regression this rule exists for: UNKNOWN is not a yes.
        assertFalse(credentialsAllowed(SocketOwner.UNKNOWN))
    }

    @Test
    fun anEndpointNobodyListensOnIsRefused() {
        // Nothing to authenticate against: the stop request could not arrive,
        // so sending a password there is only a risk.
        assertFalse(credentialsAllowed(SocketOwner.NOT_LISTENING))
    }

    /**
     * A request needs all three: the node RUNNING (the state the poll loop only
     * reaches after the listener was proved to be this app's daemon), that proof
     * itself, and a live process.
     */
    @Test
    fun aRequestNeedsRunningVerifiedAndAlive() {
        assertTrue(clientAllowed(NodeStatus.RUNNING, listenerVerified = true, alive = true))
    }

    @Test
    fun noClientBeforeTheListenerIsVerified() {
        // The start window the review found: the free port was checked before
        // the daemon bound it, so an unverified listener may be somebody else.
        assertFalse(clientAllowed(NodeStatus.RUNNING, listenerVerified = false, alive = true))
    }

    @Test
    fun noClientWhileTheNodeIsStarting() {
        for (status in NodeStatus.entries.filter { it != NodeStatus.RUNNING }) {
            assertFalse(
                "$status must not hand out a client",
                clientAllowed(status, listenerVerified = true, alive = true)
            )
        }
    }

    @Test
    fun noClientWithoutADaemon() {
        assertFalse(clientAllowed(NodeStatus.RUNNING, listenerVerified = true, alive = false))
        // A stale verification flag is not enough on its own.
        assertFalse(clientAllowed(NodeStatus.ERROR, listenerVerified = true, alive = false))
    }
}
