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
 * The Keystore is not available on every device (a broken or reset keystore, an
 * OEM build without the provider). When encryption fails the value is stored in
 * the clear, exactly as before, rather than losing the credentials: the app keeps
 * working and only the at-rest protection of that one device is weaker.
 */
internal class SecretStore(private val prefs: SharedPreferences) {

    /** The stored secret, decrypting a legacy plaintext value on the way. */
    fun read(key: String): String? {
        val encrypted = prefs.getString(encryptedKey(key), null)
        if (encrypted != null) {
            val plain = decrypt(encrypted)
            if (plain != null) return plain
            Log.w(TAG, "Stored secret '$key' could not be decrypted; a new one will be generated.")
            return null
        }
        val legacy = prefs.getString(key, null) ?: return null
        write(key, legacy)
        return legacy
    }

    fun write(key: String, value: String) {
        val encrypted = encrypt(value)
        if (encrypted == null) {
            prefs.edit().putString(key, value).remove(encryptedKey(key)).apply()
            return
        }
        prefs.edit().putString(encryptedKey(key), encrypted).remove(key).apply()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "$VERSION_PREFIX${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }.onFailure { error ->
        Log.w(TAG, "Keystore encryption unavailable: ${error.message}")
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        if (!stored.startsWith(VERSION_PREFIX)) return null
        val parts = stored.removePrefix(VERSION_PREFIX).split(':')
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.onFailure { error ->
        Log.w(TAG, "Keystore decryption failed: ${error.message}")
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
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
