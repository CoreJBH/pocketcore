package com.jbitmstr.pocketcore.core

import android.content.Context

/**
 * Process-wide access to the bundled IPv4 country table.
 *
 * The asset is decompressed once, on the IO dispatcher, the first time a peer
 * is displayed. No network request is involved at any point.
 */
object GeoIp {

    private const val ASSET = "ipv4_country.bin"

    @Volatile private var index: IpCountryIndex? = null
    @Volatile private var loading = false

    fun ensureLoaded(context: Context) {
        if (index != null) return
        synchronized(this) {
            if (index != null || loading) return
            loading = true
            try {
                val raw = context.assets.open(ASSET).use { it.readBytes() }
                // The asset is stored uncompressed; a gzip header is still
                // accepted so an older package keeps working.
                val payload = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(raw.inputStream(), 64 * 1024).use { it.readBytes() }
                } else {
                    raw
                }
                index = IpCountryIndex.parse(payload)
            } catch (error: Exception) {
                index = IpCountryIndex.empty()
            } finally {
                loading = false
            }
        }
    }

    fun isReady(): Boolean = index != null

    /** ISO code for an address such as "127.0.0.1:8333" or "[::1]:8333". */
    fun countryOf(hostOrAddress: String): String? {
        val current = index ?: return null
        val host = hostPart(hostOrAddress)
        val value = IpCountryIndex.parseIpv4(host) ?: return null
        val position = current.rangeIndex(value)
        if (position < 0) return null
        return current.countryOf(host)
    }

    fun flag(hostOrAddress: String): String = Countries.flag(countryOf(hostOrAddress))

    fun name(hostOrAddress: String): String? {
        val code = countryOf(hostOrAddress) ?: return null
        return Countries.name(code)
    }

    fun rangeCount(): Int = index?.rangeCount ?: 0

    fun countryCount(): Int = index?.countries?.size ?: 0

    /** "1.2.3.4:8333" -> "1.2.3.4", "[2001:db8::1]:8333" -> "2001:db8::1". */
    fun hostPart(address: String): String {
        val trimmed = address.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 0) return trimmed.substring(1, end)
        }
        val colon = trimmed.indexOf(':')
        if (colon < 0) return trimmed
        if (trimmed.indexOf(':', colon + 1) >= 0) return trimmed
        return trimmed.substring(0, colon)
    }
}
