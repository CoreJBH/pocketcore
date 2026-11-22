package com.corejbh.pocketcore.core

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * True only when a failure says the shared Keystore key itself is unusable.
 *
 * That is the one case in which replacing the key cannot cost a secret: a key
 * the Keystore invalidated (a lock screen change) or one whose entry cannot be
 * read decrypts nothing that was already written, so a fresh key loses only
 * values that were unreadable anyway.
 *
 * [AEADBadTagException] is deliberately not in this list. It says that one
 * stored value failed its authentication tag - it is damaged, or it was written
 * under a key that no longer exists - and not that the key is bad: every other
 * secret under that key (the RPC password, the descriptor) still decrypts
 * cleanly. Deleting the shared key over one bad entry is what would wipe them,
 * so the caller drops the single entry instead.
 */
internal fun keyIsUnusable(error: Throwable?): Boolean = when (error) {
    // KeyPermanentlyInvalidatedException is an InvalidKeyException.
    is InvalidKeyException -> true
    is UnrecoverableKeyException -> true
    else -> false
}

/**
 * Encrypts the few secrets the app keeps in its preferences with a key that
 * never leaves the Android Keystore.
 *
 * The RPC password and the watch-only descriptor are the secrets stored here.
 * Each is written as `v1:<iv>:<ciphertext>` (AES-256-GCM, a fresh IV per write)
 * under `<key>_enc`, and the plaintext key is removed. A value found under the
 * old plaintext key - the format every build before versionCode 9 (the password)
 * or 13 (the descriptor) wrote - is read once and re-encrypted in place, so an
 * upgrade keeps it.
 *
 * A descriptor is not a key, but it is still the address set of somebody's
 * wallet: in the clear it was readable by any component the app's preferences
 * were shared with, a backup or a rooted shell. It is protected the same way.
 *
 * Nothing is written in the clear, not even when the Keystore is unavailable (a
 * broken provider, an OEM build without one, a key that a lock screen change has
 * permanently invalidated). The secret then lives in memory only: [read] hands
 * it back for as long as this process runs, the preferences hold nothing, and
 * the next start has to ask for it again. A secret that cannot be protected at
 * rest is not written to disk at all.
 *
 * One key serves every secret, so a failure is read at its own scope: a value
 * that cannot be decrypted is dropped entry by entry, and only a key the
 * Keystore itself reports as gone is replaced (see [keyIsUnusable]).
 *
 * Every entry point takes [lock]. The store is reached from the UI thread, the
 * node's start path and the descriptor scan, and its key is shared: without the
 * lock a read could decrypt with a key another thread is regenerating, or lose
 * an [ephemeral] value to a concurrent write. The operations are quick (one
 * Keystore call and one preference write), so a single lock costs nothing.
 *
 * Two properties the eighth review asked for:
 *
 *  - a write is **durable** when it returns. A secret here is what a daemon that
 *    is already running authenticates with and what the next start has to find
 *    again, so the preference write is [SharedPreferences.Editor.commit] rather
 *    than `apply()`, whose disk write happens later and can be lost with the
 *    process; a caller that rotated the RPC pair before launching bitcoind would
 *    otherwise be able to leave the daemon holding a password no later start can
 *    read.
 *  - a secret is decrypted **once per process** ([decrypted]). The first read is
 *    the Keystore operation that must not sit on the main thread; every later
 *    one (a settings edit republishing the state, the start path reading the
 *    pair again) is a map lookup. The plaintext is the same value the settings
 *    state already holds in memory, and it is never written anywhere but here.
 */
@SuppressLint("ApplySharedPref")
internal class SecretStore(private val prefs: SharedPreferences) {

    private val lock = Any()

    /**
     * Secrets decrypted in this process, by key. Filled on the first [read] of a
     * value and dropped with it on [write] / [remove]: the plaintext never
     * leaves the process, and one Keystore cipher operation per secret per
     * process is all the app needs.
     */
    private val decrypted = HashMap<String, String>()

    /**
     * Secrets the Keystore would not encrypt. They last as long as this object,
     * which is the app process: the daemon started by this process accepts the
     * same value, and no copy is left behind for a later one.
     */
    private val ephemeral = HashMap<String, String>()

    /** The stored secret, decrypting a legacy plaintext value on the way. */
    fun read(key: String): String? = synchronized(lock) {
        decrypted[key]?.let { return@synchronized it }
        val encrypted = prefs.getString(encryptedKey(key), null)
        if (encrypted != null) {
            val plain = decrypt(encrypted)
            if (plain != null) {
                decrypted[key] = plain
                return@synchronized plain
            }
            // This value alone cannot be read any more (damaged, or written
            // under a key that is gone). It is dropped - and nothing else: the
            // shared key keeps decrypting the other secrets, so one corrupt
            // entry never costs the RPC password or the descriptor.
            Log.w(TAG, "Stored secret '$key' could not be decrypted; that entry alone is discarded.")
            prefs.edit().remove(encryptedKey(key)).commit()
        }
        ephemeral[key]?.let { return@synchronized it }
        // A value from a build before versionCode 9: plaintext in preferences.
        // It is encrypted now and the plaintext copy removed; the value itself is
        // returned, so the running daemon keeps its credentials.
        val legacy = prefs.getString(key, null) ?: return@synchronized null
        writeLocked(key, legacy)
        legacy
    }

    fun write(key: String, value: String) = synchronized(lock) { writeLocked(key, value) }

    private fun writeLocked(key: String, value: String) {
        val encrypted = encrypt(value)
        decrypted[key] = value
        if (encrypted == null) {
            // No usable Keystore: keep the secret for this process and make sure
            // no plaintext copy is left in the preferences.
            ephemeral[key] = value
            prefs.edit().remove(key).remove(encryptedKey(key)).commit()
            return
        }
        ephemeral.remove(key)
        // commit(), not apply(): the value has to be on disk when this returns.
        prefs.edit().putString(encryptedKey(key), encrypted).remove(key).commit()
    }

    /** Forgets a secret, leaving neither a ciphertext nor a plaintext copy. */
    fun remove(key: String) = synchronized(lock) {
        decrypted.remove(key)
        ephemeral.remove(key)
        prefs.edit().remove(key).remove(encryptedKey(key)).commit()
    }

    private fun encrypt(plain: String): String? {
        val key = usableKey() ?: return null
        val first = runCatching { encryptWith(key, plain) }
        first.getOrNull()?.let { return it }
        val error = first.exceptionOrNull()
        Log.w(TAG, "Encryption failed (${error?.javaClass?.simpleName}): ${error?.message}")
        // A cipher that failed for any other reason is not a licence to drop the
        // key every other secret is encrypted under: the value stays ephemeral.
        if (!keyIsUnusable(error)) return null
        val fresh = replaceKey() ?: return null
        return runCatching { encryptWith(fresh, plain) }
            .onFailure { Log.w(TAG, "Keystore unavailable: ${it.message}") }
            .getOrNull()
    }

    private fun decrypt(stored: String): String? {
        if (!stored.startsWith(VERSION_PREFIX)) return null
        val parts = stored.removePrefix(VERSION_PREFIX).split(':')
        if (parts.size != 2) return null
        val iv = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull() ?: return null
        val ciphertext = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return null
        val key = usableKey() ?: return null
        val first = runCatching { decryptWith(key, iv, ciphertext) }
        first.getOrNull()?.let { return it }
        val error = first.exceptionOrNull()
        if (error is AEADBadTagException) {
            // The tag did not match for this value: it is damaged, or it was
            // written under a key that is gone. The key itself is fine and still
            // decrypts every other secret, so it is left alone and the caller
            // drops this one entry.
            Log.w(TAG, "A stored secret failed its authentication tag; that entry alone is discarded.")
            return null
        }
        Log.w(TAG, "Decryption failed (${error?.javaClass?.simpleName}): ${error?.message}")
        if (!keyIsUnusable(error)) return null
        val fresh = replaceKey() ?: return null
        return runCatching { decryptWith(fresh, iv, ciphertext) }.getOrNull()
    }

    private fun encryptWith(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$VERSION_PREFIX${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decryptWith(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * The Keystore key, generated on first use.
     *
     * A key entry the Keystore cannot read is unusable for anything - it would
     * decrypt no existing secret either - so it is replaced, which is also the
     * recovery for a key a lock screen change invalidated. This is the only
     * place a key is deleted: a failed cipher operation on a single value never
     * reaches it (see [decrypt]).
     */
    private fun usableKey(): SecretKey? {
        val existing = runCatching { secretKey() }
        existing.getOrNull()?.let { return it }
        val error = existing.exceptionOrNull()
        Log.w(TAG, "Keystore key is unusable (${error?.javaClass?.simpleName}): ${error?.message}")
        return replaceKey()
    }

    /** Removes the old key and builds a clean one in its place. */
    private fun replaceKey(): SecretKey? {
        if (!deleteKey()) return null
        return runCatching { secretKey() }
            .onFailure { Log.w(TAG, "Keystore unavailable: ${it.message}") }
            .getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // An entry that can no longer be read throws here; [usableKey] then
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

    /** Deletes the key entry so the next [secretKey] builds a clean one. */
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
