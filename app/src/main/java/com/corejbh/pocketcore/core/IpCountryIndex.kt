package com.corejbh.pocketcore.core

/**
 * Offline IPv4 to country lookup.
 *
 * The table is a compact, gzip-compressed list of contiguous address ranges
 * generated from the free DB-IP "IP to Country Lite" database and shipped in
 * `assets/ipv4_country.bin.gz`. Nothing is ever sent to a third-party GeoIP
 * service, so the peer list never leaks which addresses this phone talks to.
 *
 * This class is pure Kotlin (no Android types) so the parser and the lookup can
 * be unit tested on the JVM.
 */
class IpCountryIndex private constructor(
    private val starts: IntArray,
    private val countryIndexes: ByteArray,
    val countries: List<String>
) {

    val rangeCount: Int get() = starts.size


    /** ISO 3166-1 alpha-2 code for [address], or null when unknown. */
    fun countryOf(address: String): String? {
        val value = parseIpv4(address) ?: return null
        val index = rangeIndex(value)
        if (index < 0) return null
        val country = countryIndexes[index].toInt() and 0xFF
        if (country == 0 || country > countries.size) return null
        return countries[country - 1]
    }

    /**
     * Index of the range whose start is the greatest value <= [value].
     *
     * Addresses are held as unsigned 32-bit values: everything above 127.255.255.255
     * has the sign bit set once it is an Int, so the search compares unsigned.
     */
    fun rangeIndex(value: Long): Int {
        if (starts.isEmpty()) return -1
        val target = value.toInt()
        if (java.lang.Integer.compareUnsigned(target, starts[0]) < 0) return -1
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (java.lang.Integer.compareUnsigned(starts[middle], target) <= 0) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return low
    }

    companion object {
        private const val MAGIC = "PCG3"

        /** Up to 4 groups of 7 bits, i.e. a delta that still fits in 32 bits. */
        private const val MAX_SHIFT = 28

        private const val MAX_ADDRESS = 0xFFFFFFFFL

        fun parse(payload: ByteArray): IpCountryIndex {
            require(payload.size >= 8) { "Truncated country table" }
            require(String(payload, 0, 4, Charsets.US_ASCII) == MAGIC) { "Unknown country table format" }
            var cursor = 4
            val count = (payload[cursor].toInt() and 0xFF) or ((payload[cursor + 1].toInt() and 0xFF) shl 8)
            cursor += 2
            require(payload.size >= cursor + count * 2) { "Truncated country table" }
            val countries = ArrayList<String>(count)
            repeat(count) {
                countries.add(String(payload, cursor, 2, Charsets.US_ASCII))
                cursor += 2
            }
            val starts = ArrayList<Int>(1 shl 18)
            val indexes = ArrayList<Byte>(1 shl 18)
            var address = 0L
            while (cursor < payload.size) {
                // Each record is a varint delta of the range start followed by
                // one country byte. A damaged table must fail here instead of
                // walking off the buffer or shifting a delta into nonsense.
                var shift = 0
                var delta = 0L
                while (true) {
                    require(cursor < payload.size) { "Truncated range delta" }
                    val byte = payload[cursor++].toInt() and 0xFF
                    delta = delta or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                    require(shift <= MAX_SHIFT) { "Range delta is too large" }
                }
                address += delta
                require(address <= MAX_ADDRESS) { "Range start is outside the IPv4 space" }
                require(cursor < payload.size) { "Truncated country byte" }
                starts.add(address.toInt())
                indexes.add(payload[cursor++])
            }
            require(starts.size == indexes.size) { "Truncated country table" }
            return IpCountryIndex(starts.toIntArray(), indexes.toByteArray(), countries)
        }

        fun empty(): IpCountryIndex = IpCountryIndex(IntArray(0), ByteArray(0), emptyList())

        /** Parses a dotted-quad IPv4 address into an unsigned 32-bit value. */
        fun parseIpv4(address: String): Long? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            var value = 0L
            var parts = 0
            var index = 0
            while (index < trimmed.length) {
                var octet = 0
                var digits = 0
                while (index < trimmed.length && trimmed[index].isDigit()) {
                    octet = octet * 10 + (trimmed[index] - '0')
                    digits++
                    index++
                    if (digits > 3) return null
                }
                if (digits == 0 || octet > 255) return null
                value = (value shl 8) or octet.toLong()
                parts++
                if (index < trimmed.length) {
                    if (trimmed[index] != '.') return null
                    index++
                }
            }
            return if (parts == 4) value else null
        }
    }
}

/** Region indicator emoji and country names for the peer list. */
object Countries {

    fun flag(code: String?): String {
        if (code == null || code.length != 2) return GLOBE
        val first = code[0].uppercaseChar()
        val second = code[1].uppercaseChar()
        if (first !in 'A'..'Z' || second !in 'A'..'Z') return GLOBE
        val base = 0x1F1E6
        return String(Character.toChars(base + (first - 'A'))) +
            String(Character.toChars(base + (second - 'A')))
    }

    fun name(code: String?): String {
        if (code == null) return "Unknown"
        return NAMES[code.uppercase()] ?: code.uppercase()
    }

    const val GLOBE = "\uD83C\uDF10"

    private val NAMES = mapOf(
        "AD" to "Andorra", "AE" to "United Arab Emirates", "AF" to "Afghanistan",
        "AG" to "Antigua and Barbuda", "AL" to "Albania", "AM" to "Armenia",
        "AO" to "Angola", "AR" to "Argentina", "AT" to "Austria", "AU" to "Australia",
        "AZ" to "Azerbaijan", "BA" to "Bosnia and Herzegovina", "BB" to "Barbados",
        "BD" to "Bangladesh", "BE" to "Belgium", "BF" to "Burkina Faso",
        "BG" to "Bulgaria", "BH" to "Bahrain", "BI" to "Burundi", "BJ" to "Benin",
        "BN" to "Brunei", "BO" to "Bolivia", "BR" to "Brazil", "BS" to "Bahamas",
        "BT" to "Bhutan", "BW" to "Botswana", "BY" to "Belarus", "BZ" to "Belize",
        "CA" to "Canada", "CD" to "DR Congo", "CF" to "Central African Republic",
        "CG" to "Congo", "CH" to "Switzerland", "CI" to "Cote d'Ivoire",
        "CL" to "Chile", "CM" to "Cameroon", "CN" to "China", "CO" to "Colombia",
        "CR" to "Costa Rica", "CU" to "Cuba", "CV" to "Cape Verde", "CY" to "Cyprus",
        "CZ" to "Czechia", "DE" to "Germany", "DJ" to "Djibouti", "DK" to "Denmark",
        "DM" to "Dominica", "DO" to "Dominican Republic", "DZ" to "Algeria",
        "EC" to "Ecuador", "EE" to "Estonia", "EG" to "Egypt", "ER" to "Eritrea",
        "ES" to "Spain", "ET" to "Ethiopia", "FI" to "Finland", "FJ" to "Fiji",
        "FM" to "Micronesia", "FR" to "France", "GA" to "Gabon", "GB" to "United Kingdom",
        "GD" to "Grenada", "GE" to "Georgia", "GH" to "Ghana", "GM" to "Gambia",
        "GN" to "Guinea", "GQ" to "Equatorial Guinea", "GR" to "Greece",
        "GT" to "Guatemala", "GW" to "Guinea-Bissau", "GY" to "Guyana",
        "HK" to "Hong Kong", "HN" to "Honduras", "HR" to "Croatia", "HT" to "Haiti",
        "HU" to "Hungary", "ID" to "Indonesia", "IE" to "Ireland", "IL" to "Israel",
        "IN" to "India", "IQ" to "Iraq", "IR" to "Iran", "IS" to "Iceland",
        "IT" to "Italy", "JM" to "Jamaica", "JO" to "Jordan", "JP" to "Japan",
        "KE" to "Kenya", "KG" to "Kyrgyzstan", "KH" to "Cambodia", "KI" to "Kiribati",
        "KM" to "Comoros", "KN" to "Saint Kitts and Nevis", "KP" to "North Korea",
        "KR" to "South Korea", "KW" to "Kuwait", "KY" to "Cayman Islands",
        "KZ" to "Kazakhstan", "LA" to "Laos", "LB" to "Lebanon", "LC" to "Saint Lucia",
        "LI" to "Liechtenstein", "LK" to "Sri Lanka", "LR" to "Liberia",
        "LS" to "Lesotho", "LT" to "Lithuania", "LU" to "Luxembourg", "LV" to "Latvia",
        "LY" to "Libya", "MA" to "Morocco", "MC" to "Monaco", "MD" to "Moldova",
        "ME" to "Montenegro", "MG" to "Madagascar", "MH" to "Marshall Islands",
        "MK" to "North Macedonia", "ML" to "Mali", "MM" to "Myanmar", "MN" to "Mongolia",
        "MO" to "Macao", "MR" to "Mauritania", "MT" to "Malta", "MU" to "Mauritius",
        "MV" to "Maldives", "MW" to "Malawi", "MX" to "Mexico", "MY" to "Malaysia",
        "MZ" to "Mozambique", "NA" to "Namibia", "NE" to "Niger", "NG" to "Nigeria",
        "NI" to "Nicaragua", "NL" to "Netherlands", "NO" to "Norway", "NP" to "Nepal",
        "NR" to "Nauru", "NZ" to "New Zealand", "OM" to "Oman", "PA" to "Panama",
        "PE" to "Peru", "PG" to "Papua New Guinea", "PH" to "Philippines",
        "PK" to "Pakistan", "PL" to "Poland", "PR" to "Puerto Rico", "PS" to "Palestine",
        "PT" to "Portugal", "PW" to "Palau", "PY" to "Paraguay", "QA" to "Qatar",
        "RO" to "Romania", "RS" to "Serbia", "RU" to "Russia", "RW" to "Rwanda",
        "SA" to "Saudi Arabia", "SB" to "Solomon Islands", "SC" to "Seychelles",
        "SD" to "Sudan", "SE" to "Sweden", "SG" to "Singapore", "SI" to "Slovenia",
        "SK" to "Slovakia", "SL" to "Sierra Leone", "SM" to "San Marino",
        "SN" to "Senegal", "SO" to "Somalia", "SR" to "Suriname", "SS" to "South Sudan",
        "ST" to "Sao Tome and Principe", "SV" to "El Salvador", "SY" to "Syria",
        "SZ" to "Eswatini", "TD" to "Chad", "TG" to "Togo", "TH" to "Thailand",
        "TJ" to "Tajikistan", "TL" to "Timor-Leste", "TM" to "Turkmenistan",
        "TN" to "Tunisia", "TO" to "Tonga", "TR" to "Turkey", "TT" to "Trinidad and Tobago",
        "TV" to "Tuvalu", "TW" to "Taiwan", "TZ" to "Tanzania", "UA" to "Ukraine",
        "UG" to "Uganda", "US" to "United States", "UY" to "Uruguay", "UZ" to "Uzbekistan",
        "VA" to "Vatican City", "VC" to "Saint Vincent and the Grenadines",
        "VE" to "Venezuela", "VN" to "Vietnam", "VU" to "Vanuatu", "WS" to "Samoa",
        "YE" to "Yemen", "ZA" to "South Africa", "ZM" to "Zambia", "ZW" to "Zimbabwe"
    )
}
