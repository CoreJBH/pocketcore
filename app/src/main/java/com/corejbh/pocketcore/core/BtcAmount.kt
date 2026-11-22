package com.corejbh.pocketcore.core

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * BTC amounts as exact satoshi integers.
 *
 * Core sends every amount as a JSON number with up to eight decimals, and the
 * explorer adds them up: the output values give the total paid out, and the
 * inputs' previous-output values minus that total give the fee. In `Double`
 * that arithmetic drifts, because a binary fraction cannot hold 0.1 or 0.0000282
 * exactly: 0.1 + 0.2 is 0.30000000000000004, twenty outputs of 0.00000001 do not
 * add up to 0.0000002, and a transaction whose prevouts exactly cover its
 * outputs can show a fee of -5e-17 BTC instead of zero. The amount is therefore
 * parsed once from its decimal text into a [Long] of satoshis, and every sum
 * (totals, fees, input sums) is an integer one; the `Double` figures the screen
 * still shows are derived from those satoshis, never accumulated themselves.
 */
internal object BtcAmount {

    /** Satoshis in one bitcoin. */
    const val SATS_PER_BTC = 100_000_000L

    private val satsPerBtc = BigDecimal.valueOf(SATS_PER_BTC)

    /**
     * Satoshis of a BTC amount written as Core writes it ("49.4999718",
     * "0.00002820", "1E-8"), or null when [text] is missing or not a number.
     *
     * Core never reports a fraction of a satoshi, but a value with more than
     * eight decimals is rounded to the nearest satoshi rather than rejected:
     * showing the amount the node meant is better than dropping it.
     */
    fun parse(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching {
            BigDecimal(trimmed)
                .multiply(satsPerBtc)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
    }

    /**
     * Satoshis of the amount at [key] in [json], or null when the key is absent,
     * null, or holds something that is not a number.
     */
    fun fromJson(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            // The number's own decimal text is what is parsed: going through
            // Double first is exactly the rounding this object exists to avoid.
            is Number -> parse(raw.toString())
            is String -> parse(raw)
            else -> null
        }
    }

    /** The same amount in BTC, for the display helpers. */
    fun toBtc(sats: Long): Double = sats.toDouble() / SATS_PER_BTC.toDouble()
}
