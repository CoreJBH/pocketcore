package com.corejbh.pocketcore.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The two Keystore operations a shared key needs, narrowed so a unit test can
 * drive a failing provider without an Android Keystore.
 */
internal interface KeySource {
    /** The key entry, generating it on first use. Throws when it cannot be read. */
    fun load(): SecretKey

    /** Deletes the entry, so the next [load] builds a clean key. */
    fun delete(): Boolean
}

/** [KeySource] over the Android Keystore entry the app seals its secrets with. */
internal object AndroidKeySource : KeySource {

    private const val KEYSTORE = "AndroidKeyStore"

    // The alias every build since versionCode 9 uses; renaming it would orphan
    // the secrets already sealed under it.
    private const val ALIAS = "pocketcore-rpc-credentials"

    override fun load(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // An entry that can no longer be read throws here; [KeyLadder] then
        // drops it and calls this again, so a missing entry is generated below.
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun delete(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        true
    }.getOrDefault(false)
}

/**
 * The one Keystore key every secret is sealed with, and the only place that
 * decides when it may be replaced.
 *
 * A missing entry is generated on first use. An entry the Keystore itself cannot
 * read is unusable for anything - it decrypts nothing that was already written -
 * so it is replaced, which is also the recovery for a key a lock screen change
 * invalidated. That is the whole of the recovery.
 *
 * The ninth review's finding is the other half of that sentence: only a failure
 * that says the key itself is gone ([keyIsUnusable] - `InvalidKeyException`
 * including `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`)
 * may replace it. A Keystore that is momentarily unavailable - a provider that
 * failed mid-call, an I/O error, an `IllegalStateException` from `load()` -
 * says nothing about the key, and generating a new one over that would destroy
 * every secret that is still perfectly readable. Before versionCode 19
 * `usableKey()` replaced the key on any exception at all, so one transient
 * provider hiccup cost the RPC password, the descriptor and the remembered node
 * settings together. Such a failure now returns null and leaves the entry
 * exactly where it is; the next call asks the same key again.
 */
internal class KeyLadder(private val source: KeySource) {

    /**
     * The key, replacing the entry only when the Keystore reports it as gone.
     *
     * Null means "no key right now": a transient failure, or a replacement that
     * could not be built either. The caller then keeps the secret in memory for
     * this process (see [SecretStore]) instead of writing anything down.
     */
    fun usableKey(): SecretKey? {
        val existing = runCatching { source.load() }
        existing.getOrNull()?.let { return it }
        val error = existing.exceptionOrNull()
        Log.w(TAG, "Keystore key is unusable (${error?.javaClass?.simpleName}): ${error?.message}")
        if (!keyIsUnusable(error)) return null
        return replaceKey()
    }

    /** Removes the old key and builds a clean one in its place. */
    fun replaceKey(): SecretKey? {
        if (!source.delete()) return null
        return runCatching { source.load() }
            .onFailure { Log.w(TAG, "Keystore unavailable: ${it.message}") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "PocketCore"
    }
}
