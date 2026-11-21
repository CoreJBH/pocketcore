package com.jbitmstr.pocketcore.core

/**
 * Turns what the user pastes into a watch-only descriptor that `scantxoutset`
 * accepts.
 *
 * POCKETCORE never holds a key: private keys (`xprv`/`tprv`/`zprv`/WIF and
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
        privateKeyMarkers.firstOrNull { lowered.contains(it) }?.let { marker ->
            return Parsed.Invalid(
                "This contains a private key ($marker). POCKETCORE is watch-only: remove the key and " +
                    "use the matching xpub/zpub/ypub instead."
            )
        }
        wifToken(text)?.let {
            return Parsed.Invalid(
                "That contains a WIF private key. Never paste it anywhere: POCKETCORE only needs a " +
                    "public descriptor or an extended public key."
            )
        }
        if (privateKeyMarkers.any { lowered.startsWith(it) }) {
            return Parsed.Invalid("Extended private keys are not accepted. Use the matching xpub/zpub/ypub.")
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
        return Parsed.Valid(text, null)
    }

    /** The first token of [text] that is a WIF private key, or null. */
    private fun wifToken(text: String): String? =
        text.split(descriptorTokens).firstOrNull { wifPattern.matches(it) }

    /**
     * A bare extended public key has no script type of its own. The usual
     * mapping is used (zpub/vpub belong to native segwit/taproot, ypub/upub to
     * wrapped segwit) and the receive branch wildcard 0 is assumed.
     */
    private fun wrapBareKey(key: String): Parsed {
        val prefix = key.take(4).lowercase()
        val (descriptor, kind) = when (prefix) {
            "zpub" -> "wpkh($key/0/*)" to "native segwit (wpkh)"
            "vpub" -> "tr($key/0/*)" to "taproot (tr)"
            "ypub" -> "sh(wpkh($key/0/*))" to "wrapped segwit (sh(wpkh))"
            "upub" -> "sh(wpkh($key/0/*))" to "wrapped segwit (sh(wpkh))"
            else -> "wpkh($key/0/*)" to "native segwit (wpkh)"
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
