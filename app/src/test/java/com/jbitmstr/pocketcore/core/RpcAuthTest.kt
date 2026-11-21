package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `rpcauth` line the daemon receives instead of a plaintext password.
 *
 * The vectors are what `share/rpcauth/rpcauth.py` prints for the same salt and
 * password, i.e. HMAC-SHA256 keyed with the salt over the password, which is
 * what `CheckUserAuthorized()` in `src/httprpc.cpp` recomputes.
 */
class RpcAuthTest {

    @Test
    fun hashesLikeBitcoinCore() {
        assertEquals(
            "40856b1d9ff3754a2313db9894e3ef279b47c4f6771795c723364248875e251b",
            RpcAuth.hmacHex("0123456789abcdef", "Zx9Qw2LmP4")
        )
        assertEquals(
            "6ed19b1c27179c0892f87aa761ca94bbe8893956890491c12c9252715bd0d33c",
            RpcAuth.hmacHex("s0dF9aK2mQ7xZ1pL", "Aa2Bb3Cc4Dd5Ee6Ff7Gg8Hh9Jj2Kk3Ll")
        )
        // An empty password still hashes (Core allows the pair, the app never
        // generates one).
        assertEquals(
            "40d26348f123413a9c264a4e66b1a063147a57eef4165e3d5ca8dfed615268f4",
            RpcAuth.hmacHex("SSSS0123456789ab", "")
        )
    }

    @Test
    fun writesTheUserSaltHashForm() {
        val line = RpcAuth.line("alice", "Zx9Qw2LmP4", "0123456789abcdef")
        assertEquals(
            "alice:0123456789abcdef" + "$" +
                "40856b1d9ff3754a2313db9894e3ef279b47c4f6771795c723364248875e251b",
            line
        )
        // Core splits the value on ':' and then on '$': exactly one of each.
        assertEquals(2, line.split(':').size)
        assertEquals(2, line.split('$').size)
    }

    @Test
    fun saltIsSafeForTheConfigLine() {
        repeat(64) {
            val salt = RpcAuth.randomSalt()
            assertEquals(RpcAuth.SALT_LENGTH_TOKENS, salt.length)
            assertTrue(salt, salt.all { it.isLetterOrDigit() && it.code < 128 })
            assertFalse(salt.contains(':'))
            assertFalse(salt.contains('$'))
        }
        val first = RpcAuth.randomSalt()
        val second = RpcAuth.randomSalt()
        assertTrue("two salts should differ", first != second)
    }

    @Test
    fun everyStartHasItsOwnSalt() {
        val password = NodeSettings.generateToken(32)
        val first = RpcAuth.line("user", password)
        val second = RpcAuth.line("user", password)
        assertTrue(first != second)
        // Both still authenticate against the same password.
        val saltOne = first.substringAfter(':').substringBefore('$')
        val saltTwo = second.substringAfter(':').substringBefore('$')
        assertEquals(first.substringAfter('$'), RpcAuth.hmacHex(saltOne, password))
        assertEquals(second.substringAfter('$'), RpcAuth.hmacHex(saltTwo, password))
    }
}
