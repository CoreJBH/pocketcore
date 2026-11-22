package com.corejbh.pocketcore.core

/**
 * Admits one node-policy pass per distinct environment signal per window.
 *
 * The app learns that Wi-Fi or a charger came or went from `ConnectivityManager`
 * and a power broadcast. The network callback is the noisy one: once registered
 * with `registerDefaultNetworkCallback`, `onCapabilitiesChanged` is delivered
 * whenever any capability of the default network changes - signal strength,
 * downstream bandwidth, a captive-portal probe - which on a phone is several
 * times a second. Every one of those used to refresh the label and launch a full
 * policy pass, and a policy pass takes the node mutex and can stop or start the
 * daemon; a burst of them queues passes that all re-read the same Wi-Fi state.
 *
 * The rule (versionCode 19): a signal equal to the last admitted one, inside the
 * window, is dropped. A different signal passes at once - a network that went
 * away has to pause the node without waiting for the window - and a repeat after
 * the window passes again, so a state that drifted without a callback still gets
 * re-read eventually.
 *
 * [clock] is injected, so the window is unit tested without waiting.
 */
internal class EnvironmentGate(
    private val windowMillis: Long = WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var lastSignature: String? = null
    private var lastAt = 0L

    /** True when this signal should be acted on. */
    fun shouldEvaluate(signature: String): Boolean {
        val now = clock()
        if (signature == lastSignature && now - lastAt < windowMillis) return false
        lastSignature = signature
        lastAt = now
        return true
    }

    companion object {
        /** How long a repeated signal is coalesced. */
        const val WINDOW_MS = 2_000L
    }
}

/**
 * The connectivity facts a network callback carries, as one comparable string.
 *
 * Only what the app reads is in it: whether the default network is Wi-Fi
 * (the "Wi-Fi only" gate), and the transports the label names. Signal strength,
 * bandwidth and the other capabilities deliberately are not, so a callback that
 * only moves those produces the same signature as the last one and is
 * coalesced - rather than a new policy pass.
 */
internal object NetworkSignature {

    fun of(wifi: Boolean, cellular: Boolean, ethernet: Boolean, vpn: Boolean, validated: Boolean): String =
        "wifi=${flag(wifi)} cellular=${flag(cellular)} ethernet=${flag(ethernet)} " +
            "vpn=${flag(vpn)} validated=${flag(validated)}"

    private fun flag(value: Boolean): Char = if (value) '1' else '0'
}
