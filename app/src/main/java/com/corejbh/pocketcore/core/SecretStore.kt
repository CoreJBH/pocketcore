package com.corejbh.pocketcore.core

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
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
 * that cannot be decrypted is dropped entry by entry, only a key the Keystore
 * itself reports as gone is replaced (see [KeyLadder] and [keyIsUnusable]), and
 * a Keystore that merely failed to answer leaves both the key and the stored
 * value where they are for the next attempt.
 *
 * Every entry point takes [lock]. The store is reached from the UI thread, the
 * node's start path and the descriptor scan, and its key is shared: without the
 * lock a read could decrypt with a key another thread is regenerating, or lose
 * an [ephemeral] value to a concurrent write. The operations are quick (one
 * Keystore call and one preference write), so a single lock costs nothing.
 *
 * Properties the eighth and ninth reviews asked for:
 *
 *  - a Keystore failure is read at its own scope (versionCode 19). Only a key the
 *    Keystore reports as gone is replaced, and a Keystore that merely failed to
 *    answer leaves the key and the stored ciphertext in place for the next
 *    attempt. A value that cannot be decrypted at all is still dropped, and a
 *    write the Keystore will not seal still leaves no ciphertext behind - a
 *    stale value must never outlive the value it was replaced with - but neither
 *    of those costs the shared key any more.
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

    /** The shared key, generated on first use and replaced only when it is gone. */
    private val keyLadder = KeyLadder(AndroidKeySource)

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
            when (val result = decrypt(encrypted)) {
                is Decryption.Opened -> {
                    decrypted[key] = result.plain
                    return@synchronized result.plain
                }
                // This value alone cannot be read any more (damaged, or written
                // under a key that is gone). It is dropped - and nothing else:
                // the shared key keeps decrypting the other secrets, so one
                // corrupt entry never costs the RPC password or the descriptor.
                Decryption.Damaged -> {
                    Log.w(TAG, "Stored secret '$key' could not be decrypted; that entry alone is discarded.")
                    prefs.edit().remove(encryptedKey(key)).commit()
                }
                // The Keystore did not answer (a provider failure, a transient
                // I/O error). The ciphertext is not known to be damaged, so it
                // is kept and the next read tries the same key again: dropping
                // it here would turn a passing Keystore hiccup into a lost
                // descriptor.
                Decryption.Unavailable -> Log.w(
                    TAG,
                    "Stored secret '$key' could not be read now; the entry is left for the next attempt."
                )
            }
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

    /**
     * One stored value, and whether the failure is the value's or the
     * Keystore's.
     *
     * The distinction decides what happens to the ciphertext: a damaged value is
     * dropped, while a Keystore that did not answer says nothing about this
     * value and leaves it stored (versionCode 19).
     */
    private sealed interface Decryption {
        class Opened(val plain: String) : Decryption
        data object Damaged : Decryption
        data object Unavailable : Decryption
    }

    private fun decrypt(stored: String): Decryption {
        if (!stored.startsWith(VERSION_PREFIX)) return Decryption.Damaged
        val parts = stored.removePrefix(VERSION_PREFIX).split(':')
        if (parts.size != 2) return Decryption.Damaged
        val iv = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull()
            ?: return Decryption.Damaged
        val ciphertext = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull()
            ?: return Decryption.Damaged
        val key = usableKey() ?: return Decryption.Unavailable
        val first = runCatching { decryptWith(key, iv, ciphertext) }
        first.getOrNull()?.let { return Decryption.Opened(it) }
        val error = first.exceptionOrNull()
        if (error is AEADBadTagException) {
            // The tag did not match for this value: it is damaged, or it was
            // written under a key that is gone. The key itself is fine and still
            // decrypts every other secret, so it is left alone and the caller
            // drops this one entry.
            Log.w(TAG, "A stored secret failed its authentication tag; that entry alone is discarded.")
            return Decryption.Damaged
        }
        Log.w(TAG, "Decryption failed (${error?.javaClass?.simpleName}): ${error?.message}")
        // A provider or transport failure: the key is kept, and so is the value.
        if (!keyIsUnusable(error)) return Decryption.Unavailable
        // The key was permanently invalidated, so nothing sealed under it can be
        // read again. The key is replaced (which is what keeps encryption
        // working), and this value - written by the key that is gone - stays
        // unreadable and is dropped.
        val fresh = replaceKey() ?: return Decryption.Unavailable
        return runCatching { decryptWith(fresh, iv, ciphertext) }
            .fold(
                onSuccess = { Decryption.Opened(it) },
                onFailure = { Decryption.Damaged }
            )
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
     * The decision of when it may be replaced - and when a failure has to leave
     * it alone - is [KeyLadder]'s, in one place, because a key deleted over a
     * transient error takes every secret sealed under it.
     */
    private fun usableKey(): SecretKey? = keyLadder.usableKey()

    /** Removes the old key and builds a clean one in its place. */
    private fun replaceKey(): SecretKey? = keyLadder.replaceKey()

    private fun encryptedKey(key: String): String = "$key$ENCRYPTED_SUFFIX"

    private companion object {
        const val TAG = "PocketCore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION_PREFIX = "v1:"
        const val ENCRYPTED_SUFFIX = "_enc"
    }
}
