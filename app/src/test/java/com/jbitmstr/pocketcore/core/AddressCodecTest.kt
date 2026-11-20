package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Script to address rendering used by the watch-only scan. */
class AddressCodecTest {

    @Test
    fun rendersP2wpkhAsBech32() {
        val script = "0014751e76e8199196d454941c45d1b3a323f1433bd6"
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            AddressCodec.fromScriptPubKey(script, BitcoinNetwork.MAINNET)
        )
        assertEquals(
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
            AddressCodec.fromScriptPubKey(script, BitcoinNetwork.SIGNET)
        )
    }

    @Test
    fun rendersTaprootAsBech32m() {
        val script = "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        assertEquals(
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
            AddressCodec.fromScriptPubKey(script, BitcoinNetwork.MAINNET)
        )
    }

    @Test
    fun rendersP2pkhAndP2sh() {
        assertEquals(
            "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH",
            AddressCodec.fromScriptPubKey("76a914751e76e8199196d454941c45d1b3a323f1433bd688ac", BitcoinNetwork.MAINNET)
        )
        assertEquals(
            "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            AddressCodec.fromScriptPubKey("a914b472a266d0bd89c13706a4132ccfb16f7c3b9fcb87", BitcoinNetwork.MAINNET)
        )
    }

    @Test
    fun ignoresUnknownScripts() {
        assertNull(AddressCodec.fromScriptPubKey("6a0b68656c6c6f", BitcoinNetwork.MAINNET))
        assertNull(AddressCodec.fromScriptPubKey("0014", BitcoinNetwork.MAINNET))
        assertNull(AddressCodec.fromScriptPubKey("zz", BitcoinNetwork.MAINNET))
        assertNull(AddressCodec.fromScriptPubKey("", BitcoinNetwork.MAINNET))
    }

    @Test
    fun roundTripsHex() {
        val bytes = AddressCodec.hexToBytes("00ff10")
        assertEquals(3, bytes!!.size)
        assertEquals(0, bytes[0].toInt())
        assertEquals(255, bytes[1].toInt() and 0xFF)
        assertEquals(16, bytes[2].toInt())
        assertNull(AddressCodec.hexToBytes("abc"))
    }
}
