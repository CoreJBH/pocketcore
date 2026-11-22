package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch-only descriptor intake: what is accepted, wrapped or refused. */
class DescriptorsTest {

    private val xpub =
        "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    private val tpub =
        "tpubDCqtwidUGiMWjK5JoxycqmrbE6vAK6pvFPPN1EsrhUMiTs536xc2acgYrsdUZ7cTTQVYZe9XYDyr2WGoUXJBiq7z2yo1VLd9KWaaf2htk9U"
    private val zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    // The same BIP32 key as [xpub] under the non-standard version bytes, and
    // the standard xpub/tpub the conversion must land on.
    private val ypub =
        "ypub6XJXj9Uhi7wYJp5aC8n9qwcj4wxxGLKMcsKHS5ibjZyhmgDZHPvW4Efre3WH2XK9595ShYEDTnWMDcPkoMrxddMHqik8PinQ1H3pHbCYAtS"
    private val vpub =
        "vpub5Yojp9TxG5K6kvWDh4RHDgLDZ3XcSULrsXkd5u2wbYr4cNmsXRRpC3hSaRdX2oMNrDj2T7SY5oShZkZ4eGcvEvJVEherdzKwC6rt7qutjnA"
    private val upub =
        "upub5DyUWUo37PmcudK6rhdf1bEiP5PAVrMMxREQJW94DYUBZGxeGmGFZz3JZDfw2thTSacDhdqyd969gTwVvaCuSgctNMxS45WSvNoEjEd1pdc"
    private val xpubOfZpub =
        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"

    @Test
    fun acceptsFullDescriptors() {
        listOf(
            "wpkh($xpub/0/*)",
            "tr($xpub/0/*)",
            "sh(wpkh($xpub/0/*))",
            "pkh($xpub/0/*)",
            "wsh(sortedmulti(2,$xpub/0/*,$xpub/1/*))",
            "addr(bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq)"
        ).forEach { descriptor ->
            val parsed = Descriptors.parse(descriptor)
            assertTrue("should accept $descriptor", parsed is Descriptors.Parsed.Valid)
            assertEquals(descriptor, (parsed as Descriptors.Parsed.Valid).descriptor)
        }
    }

    @Test
    fun wrapsBareExtendedPublicKeys() {
        val wpkh = Descriptors.parse(xpub) as Descriptors.Parsed.Valid
        assertEquals("wpkh($xpub/0/*)", wpkh.descriptor)
        assertTrue(wpkh.note!!.contains("wpkh"))

        val native = Descriptors.parse(zpub) as Descriptors.Parsed.Valid
        assertEquals("wpkh($xpubOfZpub/0/*)", native.descriptor)

        val wrapped = Descriptors.parse(ypub) as Descriptors.Parsed.Valid
        assertTrue(wrapped.descriptor.startsWith("sh(wpkh($xpub/"))
    }

    /**
     * Core's descriptor language accepts the standard prefixes only, so a
     * zpub/ypub/vpub/upub is rebased to xpub/tpub - the same BIP32 key, the
     * standard version bytes, checksum recomputed - before it reaches the node.
     */
    @Test
    fun convertsBareNonStandardKeys() {
        assertEquals(xpubOfZpub, Descriptors.standardKey(zpub))
        assertEquals(xpub, Descriptors.standardKey(ypub))
        assertEquals(xpub, Descriptors.standardKey(xpub))
        assertEquals(tpub, Descriptors.standardKey(vpub))
        assertEquals(tpub, Descriptors.standardKey(upub))
        assertEquals(tpub, Descriptors.standardKey(tpub))
        assertEquals(null, Descriptors.standardKey("notakey"))
    }

    @Test
    fun wrapsEveryPrefixOnItsOwnScriptType() {
        val native = Descriptors.parse(zpub) as Descriptors.Parsed.Valid
        assertEquals("wpkh($xpubOfZpub/0/*)", native.descriptor)

        // vpub is testnet's native segwit key (BIP 84), not a taproot key: an
        // earlier build wrapped it in tr(...), which scans the wrong scripts.
        val testnetNative = Descriptors.parse(vpub) as Descriptors.Parsed.Valid
        assertEquals("wpkh($tpub/0/*)", testnetNative.descriptor)

        val wrappedMainnet = Descriptors.parse(ypub) as Descriptors.Parsed.Valid
        assertEquals("sh(wpkh($xpub/0/*))", wrappedMainnet.descriptor)

        val wrappedTestnet = Descriptors.parse(upub) as Descriptors.Parsed.Valid
        assertEquals("sh(wpkh($tpub/0/*))", wrappedTestnet.descriptor)
    }

    @Test
    fun convertsKeysInsideFullDescriptors() {
        val parsed = Descriptors.parse("wpkh($zpub/0/*)") as Descriptors.Parsed.Valid
        assertEquals("wpkh($xpubOfZpub/0/*)", parsed.descriptor)
        assertTrue(parsed.note!!.contains("converted"))

        val twoKeys = Descriptors.parse("wsh(sortedmulti(2,$ypub/0/*,$vpub/1/*))") as Descriptors.Parsed.Valid
        assertEquals("wsh(sortedmulti(2,$xpub/0/*,$tpub/1/*))", twoKeys.descriptor)
    }

    @Test
    fun keepsAStandardDescriptorExactlyAsTyped() {
        val descriptor = "wpkh($xpub/0/*)"
        val parsed = Descriptors.parse(descriptor) as Descriptors.Parsed.Valid
        assertEquals(descriptor, parsed.descriptor)
        assertEquals(null, parsed.note)
    }

    @Test
    fun aNonStandardKeyWithBrokenBase58IsRefused() {
        val tampered = zpub.dropLast(1) + "t"
        val parsed = Descriptors.parse(tampered)
        assertTrue(parsed is Descriptors.Parsed.Invalid)
        assertTrue((parsed as Descriptors.Parsed.Invalid).reason.contains("could not be read"))
    }

    /**
     * The checksum Core appends covers the descriptor as written and would not
     * survive a changed key, so it is cut when a conversion happens; the bare
     * descriptor reaches the node, which answers with the checksum of what it
     * received.
     */
    @Test
    fun cutsTheChecksumWhenConverting() {
        val parsed = Descriptors.parse("wpkh($zpub/0/*)#8fhd9w27") as Descriptors.Parsed.Valid
        assertEquals("wpkh($xpubOfZpub/0/*)", parsed.descriptor)
    }

    @Test
    fun refusesPrivateKeys() {
        listOf(
            "wpkh(xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi/0/*)",
            "pkh(tprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi/0/*)",
            "cVpZ2xNBqLw8VHRYcqQ8f3Z5YBGGqjXoYYpE6uNsyXkBTRbXfDSJ",
            "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
        ).forEach { input ->
            val parsed = Descriptors.parse(input)
            assertTrue("should refuse $input", parsed is Descriptors.Parsed.Invalid)
            val reason = (parsed as Descriptors.Parsed.Invalid).reason
            assertTrue(reason, reason.contains("private key", true) || reason.contains("Private keys", true) ||
                reason.contains("WIF", true))
        }
    }

    /**
     * Regression: the WIF check only matched a bare key, so a private key inside
     * `pk(...)`, `wpkh(...)` or `tr(...)` was passed on as a descriptor.
     */
    @Test
    fun refusesWifKeysInsideDescriptorFunctions() {
        val mainnet = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
        val testnet = "cVpZ2xNBqLw8VHRYcqQ8f3Z5YBGGqjXoYYpE6uNsyXkBTRbXfDSJ"
        listOf(
            "wpkh($mainnet)",
            "pkh($mainnet)",
            "pk($mainnet)",
            "tr($mainnet)",
            "combo($mainnet/0/*)",
            "sh(wpkh($testnet))",
            "wsh(pk($mainnet))",
            "wpkh($mainnet)#8fhd9w27"
        ).forEach { input ->
            val parsed = Descriptors.parse(input)
            assertTrue("should refuse $input", parsed is Descriptors.Parsed.Invalid)
            val reason = (parsed as Descriptors.Parsed.Invalid).reason
            assertTrue(reason, reason.contains("WIF"))
            assertFalse("the key must not be echoed back", reason.contains(mainnet))
        }
    }

    /**
     * The token split must not read an extended public key as a WIF key: this xpub
     * carries a 'K' with more than 50 base58 characters after it.
     */
    @Test
    fun acceptsExtendedKeysThatCarryBase58LettersOfAWif() {
        assertTrue(xpub.contains("K"))
        assertTrue(Descriptors.parse("wpkh($xpub/0/*)") is Descriptors.Parsed.Valid)
        assertTrue(Descriptors.parse(xpub) is Descriptors.Parsed.Valid)
    }

    @Test
    fun refusesNonsense() {
        assertTrue(Descriptors.parse("") is Descriptors.Parsed.Invalid)
        assertTrue(Descriptors.parse("hello world") is Descriptors.Parsed.Invalid)
        assertTrue(Descriptors.parse("wpkh($xpub/0/*") is Descriptors.Parsed.Invalid)
        val message = (Descriptors.parse("bc1qxyz") as Descriptors.Parsed.Invalid).reason
        assertTrue(message.contains("not a descriptor"))
    }

    @Test
    fun detectsRanges() {
        assertTrue(Descriptors.hasRange("wpkh($xpub/0/*)"))
        assertTrue(!Descriptors.hasRange("addr(bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq)"))
    }
}
