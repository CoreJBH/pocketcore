package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The offline peer-country table: container format, lookup and flags. */
class IpCountryIndexTest {

    /**
     * Three ranges: 1.0.0.0-1.0.0.255 SK, 1.0.1.0-1.0.3.255 CN, everything else
     * unknown. Built with the same record layout the generator writes.
     */
    private fun expectRejected(payload: ByteArray) {
        try {
            IpCountryIndex.parse(payload)
            fail("a table of ${payload.size} bytes should be rejected")
        } catch (expected: IllegalArgumentException) {
            // The loader falls back to an empty index.
        }
    }

    private fun sampleTable(): IpCountryIndex {
        val countries = listOf("SK", "CN")
        val payload = java.io.ByteArrayOutputStream()
        payload.write("PCG3".toByteArray(Charsets.US_ASCII))
        payload.write(countries.size and 0xFF)
        payload.write((countries.size shr 8) and 0xFF)
        countries.forEach { payload.write(it.toByteArray(Charsets.US_ASCII)) }
        fun writeVarint(value: Int) {
            var remaining = value
            while (true) {
                val chunk = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) {
                    payload.write(chunk or 0x80)
                } else {
                    payload.write(chunk)
                    return
                }
            }
        }
        var previous = 0
        listOf(
            0 to "",
            (1 shl 24) to "SK",
            (1 shl 24) + (1 shl 8) to "CN",
            (1 shl 24) + (4 shl 8) to ""
        ).forEach { (start, code) ->
            writeVarint(start - previous)
            payload.write(if (code.isEmpty()) 0 else countries.indexOf(code) + 1)
            previous = start
        }
        return IpCountryIndex.parse(payload.toByteArray())
    }

    @Test
    fun parsesContainerAndFindsCountries() {
        val index = sampleTable()
        assertEquals(2, index.countries.size)
        assertEquals("SK", index.countryOf("1.0.0.42"))
        assertEquals("CN", index.countryOf("1.0.1.7"))
        assertEquals("CN", index.countryOf("1.0.3.255"))
        assertNull(index.countryOf("1.0.4.1"))
        assertNull(index.countryOf("8.8.8.8"))
    }

    /**
     * A damaged or truncated table fails with an exception the loader turns into
     * an empty index; it must never read past the buffer or shift a delta into a
     * past-the-end address.
     */
    @Test
    fun refusesTruncatedOrCorruptTables() {
        fun header(count: Int): java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream().apply {
            write("PCG3".toByteArray(Charsets.US_ASCII))
            write(count and 0xFF)
            write((count shr 8) and 0xFF)
        }
        /** A table whose declared country count and country bytes can disagree. */
        fun rawTable(count: Int, tail: ByteArray): ByteArray = header(count).apply { write(tail) }.toByteArray()
        /** A well formed header and country list, followed by raw range records. */
        fun table(countries: List<String>, records: ByteArray): ByteArray =
            header(countries.size).apply {
                countries.forEach { write(it.toByteArray(Charsets.US_ASCII)) }
                write(records)
            }.toByteArray()

        // Shorter than a header.
        expectRejected(ByteArray(0))
        expectRejected(ByteArray(4) { 'P'.code.toByte() })
        // Wrong magic.
        expectRejected(
            byteArrayOf(
                'X'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte(), '3'.code.toByte(),
                0, 0, 0, 0
            )
        )
        // Two countries announced, one present.
        expectRejected(rawTable(2, "SK".toByteArray(Charsets.US_ASCII)))
        // A range delta whose continuation bytes never end the varint.
        expectRejected(
            table(
                listOf("SK"),
                byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte())
            )
        )
        // A varint that keeps going past a 32 bit value.
        expectRejected(
            table(
                listOf("SK"),
                byteArrayOf(
                    0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                    0x80.toByte(), 0x00
                )
            )
        )
        // A delta that leaves the 32 bit address space.
        expectRejected(
            table(
                listOf("SK"),
                byteArrayOf(
                    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x1F, 0x00
                )
            )
        )
        // A delta with no country byte after it.
        expectRejected(table(listOf("SK"), byteArrayOf(0x01)))
    }

    @Test
    fun parsesDottedQuad() {
        assertEquals(1L, IpCountryIndex.parseIpv4("0.0.0.1"))
        assertEquals(4294967295L, IpCountryIndex.parseIpv4("255.255.255.255"))
        assertNull(IpCountryIndex.parseIpv4("1.2.3"))
        assertNull(IpCountryIndex.parseIpv4("1.2.3.256"))
        assertNull(IpCountryIndex.parseIpv4("1.2.3.4.5"))
        assertNull(IpCountryIndex.parseIpv4("example.com"))
    }

    @Test
    fun buildsFlagEmoji() {
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8", Countries.flag("US"))
        assertEquals("\uD83C\uDDF0\uD83C\uDDF7", Countries.flag("kr"))
        assertEquals(Countries.GLOBE, Countries.flag(null))
        assertEquals(Countries.GLOBE, Countries.flag("X"))
    }

    @Test
    fun namesCountries() {
        assertEquals("Germany", Countries.name("DE"))
        assertEquals("South Korea", Countries.name("KR"))
        assertEquals("ZZ", Countries.name("ZZ"))
    }

    @Test
    fun splitsHostFromAddress() {
        assertEquals("1.2.3.4", GeoIp.hostPart("1.2.3.4:8333"))
        assertEquals("2001:db8::1", GeoIp.hostPart("[2001:db8::1]:8333"))
        assertEquals("2001:db8::1", GeoIp.hostPart("2001:db8::1"))
        assertEquals("1.2.3.4", GeoIp.hostPart("1.2.3.4"))
    }

    @Test
    fun bundledAssetIsPresentAndParseable() {
        val asset = java.io.File("src/main/assets/ipv4_country.bin")
        if (!asset.exists()) return
        val raw = asset.readBytes()
        val payload = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        val index = IpCountryIndex.parse(payload)
        assertTrue("country table should cover the address space", index.rangeCount > 100_000)
        assertTrue(index.countries.size > 200)
        // Addresses of real peers seen on mainnet with their DB-IP country, plus
        // ranges above 127.255.255.255 (sign bit set) to guard the unsigned search.
        assertEquals("US", index.countryOf("47.158.166.202"))
        assertEquals("US", index.countryOf("172.81.132.46"))
        assertEquals("CA", index.countryOf("38.102.125.74"))
        assertEquals("CA", index.countryOf("74.206.131.251"))
        assertEquals("DK", index.countryOf("87.62.101.114"))
        assertEquals("DE", index.countryOf("91.98.226.36"))
        assertEquals("US", index.countryOf("24.193.1.236"))
        assertEquals("AU", index.countryOf("1.1.1.1"))
        assertEquals("AU", index.countryOf(GeoIp.hostPart("1.1.1.1:8333")))
        assertEquals("KR", index.countryOf("121.130.0.1"))
        assertEquals("KR", index.countryOf("211.234.1.1"))
        assertNull(index.countryOf(GeoIp.hostPart("[2001:db8::1]:8333")))
        assertFalse(index.countries.contains(""))
    }
}
