package com.corejbh.pocketcore.core

import java.security.MessageDigest

/**
 * Renders an address from a scriptPubKey.
 *
 * `scantxoutset` reports an unspent output's script as hex, not as an address,
 * so the watch-only view turns it into the usual form: base58 for P2PKH/P2SH and
 * bech32/bech32m for segwit and taproot programs.
 */
object AddressCodec {

    fun fromScriptPubKey(scriptHex: String, network: BitcoinNetwork): String? {
        val script = scriptHex.lowercase()
        val bytes = hexToBytes(script) ?: return null
        if (bytes.size < 4) return null

        // P2PKH: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
        if (bytes.size == 25 && bytes[0] == 0x76.toByte() && bytes[1] == 0xa9.toByte() &&
            bytes[2] == 0x14.toByte() && bytes[23] == 0x88.toByte() && bytes[24] == 0xac.toByte()
        ) {
            val payload = bytes.copyOfRange(3, 23)
            return base58Check(byteArrayOf(p2pkhVersion(network)) + payload)
        }
        // P2SH: OP_HASH160 <20> OP_EQUAL
        if (bytes.size == 23 && bytes[0] == 0xa9.toByte() && bytes[1] == 0x14.toByte() &&
            bytes[22] == 0x87.toByte()
        ) {
            val payload = bytes.copyOfRange(2, 22)
            return base58Check(byteArrayOf(p2shVersion(network)) + payload)
        }
        // Segwit v0..v16: OP_0 / OP_1..OP_16 followed by a 2..40 byte program.
        val version = bytes[0].toInt() and 0xFF
        val witnessVersion = when (version) {
            0x00 -> 0
            in 0x51..0x60 -> version - 0x50
            else -> -1
        }
        if (witnessVersion >= 0) {
            val length = bytes[1].toInt() and 0xFF
            if (length in 2..40 && bytes.size == 2 + length) {
                val program = bytes.copyOfRange(2, bytes.size)
                if (witnessVersion == 0 && length != 20 && length != 32) return null
                return segwitAddress(humanReadablePart(network), witnessVersion, program)
            }
        }
        return null
    }

    fun humanReadablePart(network: BitcoinNetwork): String =
        if (network == BitcoinNetwork.MAINNET) "bc" else "tb"

    private fun p2pkhVersion(network: BitcoinNetwork): Byte =
        if (network == BitcoinNetwork.MAINNET) 0x00.toByte() else 0x6f.toByte()

    private fun p2shVersion(network: BitcoinNetwork): Byte =
        if (network == BitcoinNetwork.MAINNET) 0x05.toByte() else 0xc4.toByte()

    // --- bech32 / bech32m (BIP 173 and BIP 350) --------------------------

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    fun segwitAddress(hrp: String, witnessVersion: Int, program: ByteArray): String? {
        val data = ArrayList<Int>(1 + program.size * 2)
        data.add(witnessVersion)
        data.addAll(convertBits(program.map { it.toInt() and 0xFF }, 8, 5, true) ?: return null)
        val constant = if (witnessVersion == 0) BECH32_CONST else BECH32M_CONST
        val checksum = createChecksum(hrp, data, constant)
        val builder = StringBuilder(hrp).append('1')
        (data + checksum).forEach { builder.append(CHARSET[it]) }
        return builder.toString()
    }

    private fun createChecksum(hrp: String, data: List<Int>, constant: Int): List<Int> {
        val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor constant
        return (0 until 6).map { (polymod shr (5 * (5 - it))) and 31 }
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val high = hrp.map { it.code shr 5 }
        val low = hrp.map { it.code and 31 }
        return high + listOf(0) + low
    }

    private fun polymod(values: List<Int>): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (value in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (index in 0 until 5) {
                if ((top shr index) and 1 == 1) chk = chk xor generator[index]
            }
        }
        return chk
    }

    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var accumulator = 0
        var bits = 0
        val result = ArrayList<Int>()
        val maxValue = (1 shl to) - 1
        for (value in data) {
            if (value < 0 || value shr from != 0) return null
            accumulator = (accumulator shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                result.add((accumulator shr bits) and maxValue)
            }
        }
        if (pad) {
            if (bits > 0) result.add((accumulator shl (to - bits)) and maxValue)
        } else if (bits >= from || ((accumulator shl (to - bits)) and maxValue) != 0) {
            return null
        }
        return result
    }

    // --- base58check ------------------------------------------------------

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58Check(payload: ByteArray): String {
        val checksum = doubleSha256(payload).copyOfRange(0, 4)
        val full = payload + checksum
        var value = java.math.BigInteger(1, full)
        val builder = StringBuilder()
        val base = java.math.BigInteger.valueOf(58)
        while (value.signum() > 0) {
            val division = value.divideAndRemainder(base)
            builder.append(BASE58[division[1].toInt()])
            value = division[0]
        }
        for (byte in full) {
            if (byte.toInt() == 0) builder.append('1') else break
        }
        return builder.reverse().toString()
    }

    fun doubleSha256(input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(input))
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
