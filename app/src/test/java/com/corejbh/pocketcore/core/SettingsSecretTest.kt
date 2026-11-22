package com.corejbh.pocketcore.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an RPC credential half may be generated (versionCode 21).
 *
 * `NodeSettings` generates the user and the password only for a store that holds
 * neither a ciphertext nor the plaintext key an earlier build wrote. The rule is
 * one function because both halves need it, and because the interesting case is
 * the one that is easy to get wrong: a value that exists but cannot be opened
 * right now must not be replaced - the stored ciphertext is what the next read
 * decrypts, and overwriting it changes the pair a running daemon authenticates
 * with.
 */
class SettingsSecretTest {

    @Test
    fun aFreshStoreHasToGenerateTheCredential() {
        assertTrue(secretMissing(encryptedPresent = false, plaintextPresent = false))
    }

    @Test
    fun aStoredCiphertextIsNotReplaced() {
        assertFalse(secretMissing(encryptedPresent = true, plaintextPresent = false))
    }

    @Test
    fun aPlaintextValueFromAnEarlierBuildIsMigratedRatherThanRegenerated() {
        // SecretStore.read hands the legacy value back and re-encrypts it, so the
        // pair the installed app has been using survives the upgrade.
        assertFalse(secretMissing(encryptedPresent = false, plaintextPresent = true))
        assertFalse(secretMissing(encryptedPresent = true, plaintextPresent = true))
    }
}
