package com.corejbh.pocketcore.core

/**
 * Cleaning and validation for a pasted raw signed transaction.
 *
 * Wallets and QR codes wrap a signed transaction in spaces, tabs and newlines,
 * and some tools prefix it with `0x` or quotes. All of that is removed before
 * the hex is handed to `sendrawtransaction`, so a correctly signed transaction
 * is never rejected only because of how it was copied.
 */
object RawTransaction {

    sealed class Parsed {
        data class Valid(val hex: String) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    fun clean(input: String): String {
        val builder = StringBuilder(input.length)
        for (character in input) {
            if (character.isWhitespace()) continue
            builder.append(character)
        }
        var text = builder.toString()
        text = text.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("`")
        if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2)
        return text.trim()
    }

    fun isHex(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun parse(input: String): Parsed {
        val hex = clean(input)
        if (hex.isEmpty()) return Parsed.Invalid("Paste a signed transaction in hex first.")
        if (!isHex(hex)) {
            val bad = hex.firstOrNull { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }
            return Parsed.Invalid("That is not hexadecimal: '$bad' is not a hex character.")
        }
        if (hex.length % 2 != 0) return Parsed.Invalid("The hex is ${hex.length} characters, which is not a whole number of bytes.")
        if (hex.length < MIN_HEX_LENGTH) return Parsed.Invalid("This is too short to be a transaction (${hex.length} characters).")
        return Parsed.Valid(hex)
    }

    /** Short "12 bytes" style description used in the UI while typing. */
    fun byteLength(input: String): Int = clean(input).length / 2

    private const val MIN_HEX_LENGTH = 20
}
