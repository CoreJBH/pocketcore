package com.corejbh.pocketcore.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * How a SecretStore failure is scoped.
 *
 * One Keystore key serves the RPC password and the watch-only descriptor, so
 * the question "may this failure delete the key?" decides whether one bad entry
 * takes the other secrets with it. versionCode 14 fixed a store that deleted the
 * shared key on any failure, including a single value whose GCM tag did not
 * match - which wiped the password and the descriptor on the way out.
 */
class SecretStoreKeyTest {

    @Test
    fun aBadAuthenticationTagIsNotAKeyFailure() {
        // The regression: this one entry is damaged, the key is fine, and every
        // other secret under it still decrypts.
        assertFalse(keyIsUnusable(AEADBadTagException("tag mismatch")))
    }

    @Test
    fun anInvalidatedKeyIsAKeyFailure() {
        // KeyPermanentlyInvalidatedException (a lock screen change) is an
        // InvalidKeyException, and it makes every ciphertext unreadable, so
        // replacing the key costs nothing that was still usable.
        assertFalse(keyIsUnusable(AEADBadTagException()))
        assertTrue(keyIsUnusable(InvalidKeyException("key permanently invalidated")))
    }

    @Test
    fun anUnreadableKeyEntryIsAKeyFailure() {
        assertTrue(keyIsUnusable(UnrecoverableKeyException("entry unreadable")))
    }

    @Test
    fun otherFailuresLeaveTheKeyAlone() {
        // A provider or transport problem says nothing about the key: dropping it
        // would destroy secrets that are perfectly readable.
        assertFalse(keyIsUnusable(IOException("keystore provider failed")))
        assertFalse(keyIsUnusable(IllegalStateException("unexpected")))
        assertFalse(keyIsUnusable(null))
    }
}
