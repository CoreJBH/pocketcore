package com.corejbh.pocketcore.core

/**
 * Turns what the user pastes into a watch-only descriptor that `scantxoutset`
 * accepts.
 *
 * PocketCore never holds a key: private keys (`xprv`/`tprv`/`zprv`/WIF and
 * friends) are rejected before anything is sent to the node, and the rest of
 * the pipeline only ever sees a public descriptor.
 */
object Descriptors {

    /** Highest address index scanned for a ranged descriptor (wildcard branch 0). */
    const val RANGE_END = 999

    sealed class Parsed {
        data class Valid(val descriptor: String, val note: String?) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    private val privateKeyMarkers = listOf(
        "xprv", "tprv", "uprv", "vprv", "yprv", "zprv", "dprv"
    )

    /**
     * Version bytes of the extended private keys a marker stands for.
     *
     * A Base58Check decode is what decides this, not the letters: a key written
     * with a prefix the list above does not carry is still refused when its
     * version bytes say private (versionCode 20).
     */
    private val PRIVATE_VERSION_BYTES = mapOf(
        "0488ade4" to "xprv",
        "04358394" to "tprv",
        "044a4e28" to "uprv",
        "045f18bc" to "vprv",
        "049d7878" to "yprv",
        "04b2430c" to "zprv"
    )

    /**
     * Base58 version prefixes Core's descriptor language accepts.
     *
     * A zpub, ypub, vpub or upub is the same BIP32 key as an xpub or tpub with
     * different version bytes (BIP 84/49/380), but Core's descriptor language
     * accepts the standard prefixes only, so a scan with the original key fails
     * inside the node with "is not a valid descriptor". [VERSION_BYTES] carries
     * the standard version each prefix is rebased to.
     */
    private val VERSION_BYTES = mapOf(
        "zpub" to byteArrayOf(0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte()),
        "ypub" to byteArrayOf(0x04.toByte(), 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte()),
        "vpub" to byteArrayOf(0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte()),
        "upub" to byteArrayOf(0x04.toByte(), 0x35.toByte(), 0x87.toByte(), 0xCF.toByte())
    )

    /** Byte length of a BIP32 extended key: 4 version + 74 body. */
    private const val EXTENDED_KEY_BYTES = 78

    // Base58 WIF: 5... (uncompressed mainnet), K.../L... (compressed mainnet),
    // 9.../c... (testnet). Tested on every token of the input, so a key inside a
    // descriptor function - pk(5Hue...), wpkh(...), tr(5Hue...) - is caught as
    // well as a bare one.
    private val wifPattern = Regex("^[5KL9c][1-9A-HJ-NP-Za-km-z]{50,51}$")

    /**
     * Descriptor syntax and checksum separators. Splitting on them keeps a base58
     * key inside a function a token of its own, and keeps the long letters of an
     * xpub from being read as one (an xpub is not split, so a 'K' in its middle
     * cannot start a match).
     */
    private val descriptorTokens = Regex("[(),\\[\\]/*:;\"'#\\s]+")

    private val bareKeyPattern = Regex("^(xpub|tpub|ypub|zpub|upub|vpub)[1-9A-HJ-NP-Za-km-z]{40,120}$")

    private val descriptorFunctions = listOf(
        "pk(", "pkh(", "wpkh(", "sh(", "wsh(", "tr(", "combo(", "addr(", "raw(",
        "multi(", "sortedmulti(", "multi_a(", "sortedmulti_a("
    )

    fun parse(raw: String): Parsed {
        val text = raw.filterNot { it.isWhitespace() }
        if (text.isEmpty()) return Parsed.Invalid("Paste a descriptor or an extended public key first.")
        val lowered = text.lowercase()
        privateKeyIn(text)?.let { marker ->
            return Parsed.Invalid(
                "This contains a private key ($marker). PocketCore is watch-only: remove the key and " +
                    "use the matching xpub/zpub/ypub instead."
            )
        }
        wifToken(text)?.let {
            return Parsed.Invalid(
                "That contains a WIF private key. Never paste it anywhere: PocketCore only needs a " +
                    "public descriptor or an extended public key."
            )
        }
        if (bareKeyPattern.matches(text)) {
            return wrapBareKey(text)
        }
        val startsWithFunction = descriptorFunctions.any { lowered.startsWith(it) }
        if (!startsWithFunction) {
            val hint = if (lowered.contains("(")) {
                "Only pk, pkh, wpkh, sh, wsh, tr, combo, addr, raw and multi descriptors are understood."
            } else {
                "Expected a descriptor such as wpkh(xpub.../0/*) or a bare xpub/zpub/ypub key."
            }
            return Parsed.Invalid("This is not a descriptor. $hint")
        }
        if (lowered.contains("(") && lowered.count { it == '(' } != lowered.count { it == ')' }) {
            return Parsed.Invalid("The descriptor has unbalanced parentheses.")
        }
        val standard = standardKeysIn(text)
            ?: return Parsed.Invalid(
                "An extended public key in this descriptor could not be read (bad Base58 or unexpected " +
                    "length). Check that it was pasted completely."
            )
        return Parsed.Valid(standard.first, standard.second)
    }

    /**
     * The key [key] with a standard version prefix, or null when it cannot be
     * read: Base58 with a verified checksum, a 78-byte extended key, and - for
     * an already standard key - the key unchanged.
     */
    fun standardKey(key: String): String? {
        val prefix = key.take(4)
        val version = VERSION_BYTES[prefix] ?: return if (prefix == "xpub" || prefix == "tpub") key else null
        val payload = AddressCodec.base58CheckPayload(key) ?: return null
        if (payload.size != EXTENDED_KEY_BYTES) return null
        return AddressCodec.base58Check(version + payload.copyOfRange(4, payload.size))
    }

    /** A key pasted with Core's own checksum suffix ("#8f8i2c0e"). */
    private const val CHECKSUM_SEPARATOR = '#'

    /**
     * Every non-standard extended key inside [text], converted.
     *
     * Returns the descriptor unchanged when it holds none, so a pasted xpub
     * descriptor - including its checksum - reaches the node as typed. The
     * match is anchored at a token boundary (a Base58 character before the
     * prefix means the letters are part of a longer key), and the checksum
     * suffix is cut before converting: it covers the descriptor as written and
     * would not survive a changed key, while the node accepts the bare form
     * and answers with the checksum of what it received.
     *
     * Null when a matched key cannot be converted.
     */
    private fun standardKeysIn(text: String): Pair<String, String?>? {
        val body = text.substringBefore(CHECKSUM_SEPARATOR)
        val matches = nonStandardKey.findAll(body).toList()
        if (matches.isEmpty()) return text to null
        var failed = false
        val converted = nonStandardKey.replace(body) { match ->
            standardKey(match.value) ?: run { failed = true; match.value }
        }
        if (failed) return null
        return converted to CONVERSION_NOTE
    }

    /**
     * Matches a whole zpub/ypub/vpub/upub key, but never the same letters
     * inside a longer Base58 run: the character before the prefix must not be
     * a Base58 character.
     */
    private val nonStandardKey = Regex(
        "(?<![${BASE58_CLASS}])(zpub|ypub|vpub|upub)[${BASE58_CLASS}]+"
    )

    /** Base58, as a regex character class ([1-9A-HJ-NP-Za-km-z]). */
    private const val BASE58_CLASS = "1-9A-HJ-NP-Za-km-z"

    private const val CONVERSION_NOTE =
        "Non-standard extended keys (zpub/ypub/vpub/upub) were converted to the xpub/tpub form the " +
            "node's descriptors accept - the same key, standard version bytes."

    /** The first token of [text] that is a WIF private key, or null. */
    private fun wifToken(text: String): String? =
        text.split(descriptorTokens).firstOrNull { wifPattern.matches(it) }

    /**
     * The private key marker found in [text], or null when it holds none.
     *
     * The search is case-sensitive and anchored on a token, because Base58 is
     * case-sensitive: the letters of "xprv" or "zprv" can sit inside a
     * perfectly good xpub or zpub - as "XPRV", or in lowercase by chance - and
     * lowercasing the whole input before looking for the marker refused valid
     * public keys (versionCode 20). A key that really is private is still
     * refused however it was written: by the marker at the start of its token,
     * and by the version bytes a Base58Check decode of an extended key reveals.
     */
    private fun privateKeyIn(text: String): String? {
        for (token in text.split(descriptorTokens)) {
            privateKeyMarkers.firstOrNull { token.startsWith(it) }?.let { return it }
            val version = extendedKeyVersion(token) ?: continue
            PRIVATE_VERSION_BYTES[version]?.let { return it }
        }
        return null
    }

    /**
     * The four version bytes of an extended key, as lowercase hex, or null when
     * [token] is not a Base58Check extended key.
     */
    private fun extendedKeyVersion(token: String): String? {
        // Shorter than any extended key: there is nothing here to decode.
        if (token.length < 100) return null
        val payload = AddressCodec.base58CheckPayload(token) ?: return null
        if (payload.size != EXTENDED_KEY_BYTES) return null
        val hex = StringBuilder(8)
        for (index in 0 until 4) {
            val value = payload[index].toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * A bare extended public key has no script type of its own. The usual
     * mapping is used (zpub/vpub belong to native segwit, ypub/upub to wrapped
     * segwit) and the receive branch wildcard 0 is assumed. The key itself is
     * rebased to the standard prefix Core's descriptors accept before it is
     * wrapped, so the scan works with the original key's own addresses.
     */
    private fun wrapBareKey(key: String): Parsed {
        val standard = standardKey(key) ?: return Parsed.Invalid(
            "This extended key could not be read (bad Base58 or unexpected length). Check that it " +
                "was pasted completely."
        )
        val (descriptor, kind) = when (key.take(4)) {
            "ypub", "upub" -> "sh(wpkh($standard/0/*))" to "wrapped segwit (sh(wpkh))"
            "vpub" -> "wpkh($standard/0/*)" to "native segwit on testnet (wpkh)"
            else -> "wpkh($standard/0/*)" to "native segwit (wpkh)"
        }
        return Parsed.Valid(
            descriptor,
            "Read as $kind on the receive branch 0 (wildcard). Use the full descriptor instead if this key " +
                "belongs to a change branch or another script type."
        )
    }

    /** True when a descriptor carries a wildcard and therefore needs a range. */
    fun hasRange(descriptor: String): Boolean = descriptor.contains("*")
}
