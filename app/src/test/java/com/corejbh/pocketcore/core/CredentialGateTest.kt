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
}
