package com.jbitmstr.pocketcore.core

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `-rpcauth` for the generated bitcoin.conf.
 *
 * Bitcoin Core accepts an RPC user as `user:salt$hash`, where `hash` is
 * HMAC-SHA256 keyed with the salt and computed over the password
 * (`src/httprpc.cpp`, `CheckUserAuthorized`). The client still connects with the
 * plain `rpcuser` / `rpcpassword` pair, so the daemon authenticates the app while
 * the configuration file on disk never holds the password itself.
 *
 * Pure Kotlin, so the hash is unit tested against vectors produced the way
 * `share/rpcauth/rpcauth.py` produces them.
 */
object RpcAuth {

    /** Same alphabet Core's own script draws its salt from; no ':' and no '$'. */
    private const val SALT_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    const val SALT_LENGTH_TOKENS = 16

    private const val HEX = "0123456789abcdef"

    fun randomSalt(random: SecureRandom = SecureRandom()): String {
        val builder = StringBuilder(SALT_LENGTH_TOKENS)
        repeat(SALT_LENGTH_TOKENS) { builder.append(SALT_ALPHABET[random.nextInt(SALT_ALPHABET.length)]) }
        return builder.toString()
    }

    fun hmacHex(salt: String, password: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return hex(mac.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    /** One `rpcauth=` value: `user:salt$hmac`. */
    fun line(user: String, password: String, salt: String = randomSalt()): String =
        "$user:$salt\$${hmacHex(salt, password)}"

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
