package com.corejbh.pocketcore.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the few secrets the app keeps in its preferences with a key that
 * never leaves the Android Keystore.
 *
 * The RPC password is the only secret stored here. It is written as
 * `v1:<iv>:<ciphertext>` (AES-256-GCM, a fresh IV per write) under
 * `<key>_enc`, and the plaintext key is removed. A value found under the old
 * plaintext key - the format every build before versionCode 9 wrote - is read
 * once and re-encrypted in place, so an upgrade keeps the same password and the
 * daemon and any wallet conf built from it keep working.
 *
 * Nothing is written in the clear, not even when the Keystore is unavailable (a
 * broken provider, an OEM build without one, a key that a lock screen change has
 * permanently invalidated). The secret then lives in memory only: [read] hands
 * it back for as long as this process runs, the preferences hold nothing, and
 * the next start generates a new one. A password that cannot be protected at
 * rest is not written to disk at all.
 */
internal class SecretStore(private val prefs: SharedPreferences) {

    /**
     * Secrets the Keystore would not encrypt. They last as long as this object,
     * which is the app process: the daemon started by this process accepts the
     * same value, and no copy is left behind for a later one.
     */
    private val ephemeral = HashMap<String, String>()

    /** The stored secret, decrypting a legacy plaintext value on the way. */
    fun read(key: String): String? {
        val encrypted = prefs.getString(encryptedKey(key), null)
        if (encrypted != null) {
            val plain = decrypt(encrypted)
            if (plain != null) return plain
            // The ciphertext cannot be read any more: the key was invalidated, or
            // the entry is damaged. It is dropped, so the next secret is written
            // under a cleanly generated key instead of failing on every read.
            Log.w(TAG, "Stored secret '$key' could not be decrypted; it is discarded.")
            prefs.edit().remove(encryptedKey(key)).apply()
        }
        ephemeral[key]?.let { return it }
        // A value from a build before versionCode 9: plaintext in preferences.
        // It is encrypted now and the plaintext copy removed; the value itself is
        // returned, so the running daemon keeps its credentials.
        val legacy = prefs.getString(key, null) ?: return null
        write(key, legacy)
        return legacy
    }

    fun write(key: String, value: String) {
        val encrypted = encrypt(value)
        if (encrypted == null) {
            // No usable Keystore: keep the secret for this process and make sure
            // no plaintext copy is left in the preferences.
            ephemeral[key] = value
            prefs.edit().remove(key).remove(encryptedKey(key)).apply()
            return
        }
        ephemeral.remove(key)
        prefs.edit().putString(encryptedKey(key), encrypted).remove(key).apply()
    }

    private fun encrypt(plain: String): String? = withKey { key ->
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "$VERSION_PREFIX${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        if (!stored.startsWith(VERSION_PREFIX)) return null
        val parts = stored.removePrefix(VERSION_PREFIX).split(':')
        if (parts.size != 2) return null
        val iv = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull() ?: return null
        val ciphertext = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return null
        return withKey { key ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    /**
     * Runs [block] with the Keystore key.
     *
     * A key the Keystore refuses is deleted and the block runs once more on a
     * newly generated one. That is the clean recovery for
     * `KeyPermanentlyInvalidatedException` - thrown when the screen lock that
     * protected the key was removed or changed - and for a key entry that became
     * unreadable in any other way: the ciphertext written under the old key is
     * unusable either way, and this way the app keeps working with a fresh secret
     * instead of failing every time it needs the RPC password.
     */
    private fun <T : Any> withKey(block: (SecretKey) -> T): T? {
        val first = runCatching { block(secretKey()) }
        if (first.isSuccess) return first.getOrNull()
        val error = first.exceptionOrNull()
        Log.w(TAG, "Keystore key is unusable (${error?.javaClass?.simpleName}): ${error?.message}")
        deleteKey()
        return runCatching { block(secretKey()) }
            .onFailure { retry -> Log.w(TAG, "Keystore unavailable: ${retry.message}") }
            .getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // An entry that can no longer be read throws here; [withKey] then drops
        // it and calls this again, so a missing entry is generated below.
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

    /** Removes the key so the next [secretKey] builds a clean one. */
    private fun deleteKey(): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        true
    }.getOrDefault(false)

    private fun encryptedKey(key: String): String = "$key$ENCRYPTED_SUFFIX"

    private companion object {
        const val TAG = "PocketCore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pocketcore-rpc-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION_PREFIX = "v1:"
        const val ENCRYPTED_SUFFIX = "_enc"
    }
}
