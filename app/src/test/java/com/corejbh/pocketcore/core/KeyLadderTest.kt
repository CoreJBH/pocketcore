package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey

/**
 * When the shared Keystore key may be replaced.
 *
 * One key seals the RPC password, the watch-only descriptor and the descriptor
 * scan result, so creating a new one is not free: everything written under the
 * old key becomes unreadable. versionCode 19 fixed `usableKey()` replacing the
 * key on any exception, which turned one transient provider failure into three
 * lost secrets.
 */
class KeyLadderTest {

    private class FakeKey(private val id: String) : SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String = "RAW"
        override fun getEncoded(): ByteArray = id.toByteArray()
        override fun toString(): String = "FakeKey($id)"
    }

    /** A Keystore whose entry fails the loads it is told to fail, in order. */
    private class FakeSource {
        val failures = ArrayDeque<Throwable>()
        var loads = 0
        var deletes = 0
        var deleteWorks = true
        var key: SecretKey = FakeKey("original")

        val source = object : KeySource {
            override fun load(): SecretKey {
                loads++
                failures.removeFirstOrNull()?.let { throw it }
                return key
            }

            override fun delete(): Boolean {
                deletes++
                if (!deleteWorks) return false
                key = FakeKey("fresh")
                return true
            }
        }
    }

    @Test
    fun theEntryIsUsedWhenItReads() {
        val device = FakeSource()
        val key = KeyLadder(device.source).usableKey()
        assertSame(device.key, key)
        assertEquals(0, device.deletes)
    }

    @Test
    fun aTransientFailureKeepsTheKey() {
        val device = FakeSource()
        device.failures.addLast(IOException("keystore provider failed"))
        val ladder = KeyLadder(device.source)
        assertNull("no key right now", ladder.usableKey())
        assertEquals("the key is not deleted for a provider failure", 0, device.deletes)

        // The provider comes back: the same entry is used, nothing was lost.
        val key = ladder.usableKey()
        assertNotNull(key)
        assertSame("the original key is still there", device.key, key)
        assertEquals(0, device.deletes)
    }

    @Test
    fun anIllegalStateFromLoadIsTransientToo() {
        val device = FakeSource()
        device.failures.addLast(IllegalStateException("Keystore not initialized"))
        assertNull(KeyLadder(device.source).usableKey())
        assertEquals(0, device.deletes)
    }

    @Test
    fun aPermanentlyInvalidatedKeyIsReplaced() {
        val device = FakeSource()
        // KeyPermanentlyInvalidatedException extends InvalidKeyException, which
        // is how the Keystore reports a key a lock screen change killed.
        device.failures.addLast(InvalidKeyException("key permanently invalidated"))
        val key = KeyLadder(device.source).usableKey()
        assertNotNull("a fresh key is built", key)
        assertEquals("the dead entry is deleted once", 1, device.deletes)
        assertEquals("and loaded again to generate the new one", 2, device.loads)
        assertSame(device.key, key)
    }

    @Test
    fun anUnreadableEntryIsReplaced() {
        val device = FakeSource()
        device.failures.addLast(UnrecoverableKeyException("entry unreadable"))
        assertNotNull(KeyLadder(device.source).usableKey())
        assertEquals(1, device.deletes)
    }

    @Test
    fun aKeyThatCannotBeDeletedIsNotPretendedTo() {
        val device = FakeSource()
        device.failures.addLast(UnrecoverableKeyException("entry unreadable"))
        device.deleteWorks = false
        assertNull(KeyLadder(device.source).usableKey())
        assertEquals(1, device.deletes)
    }
}
