package com.jbitmstr.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch-only descriptor intake: what is accepted, wrapped or refused. */
class DescriptorsTest {

    private val xpub =
        "xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz"
    private val zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

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
        assertEquals("wpkh($zpub/0/*)", native.descriptor)

        val ypub = Descriptors.parse("ypub" + xpub.drop(4)) as Descriptors.Parsed.Valid
        assertTrue(ypub.descriptor.startsWith("sh(wpkh("))

        val vpub = Descriptors.parse("vpub" + xpub.drop(4)) as Descriptors.Parsed.Valid
        assertTrue(vpub.descriptor.startsWith("tr("))
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
