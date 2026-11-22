package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-space check before a snapshot is copied or loaded (eighth review).
 *
 * A UTXO snapshot is gigabytes: the import used to copy until the volume was
 * full, and the load made Core start writing a chainstate it could not finish.
 * Both left a half file and a user who had waited for nothing, so the size is
 * compared with the free space on the node's volume before anything is opened.
 */
class StorageBudgetTest {

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun aCopyNeedsItsBytesPlusTheReserve() {
        assertEquals(3 * gib + StorageBudget.RESERVE_BYTES, StorageBudget.requiredBytes(3 * gib))
        assertEquals(0L, StorageBudget.requiredBytes(0L))
        assertEquals(0L, StorageBudget.requiredBytes(-1L))
        // An absurd size saturates instead of overflowing into a negative need.
        assertEquals(Long.MAX_VALUE, StorageBudget.requiredBytes(Long.MAX_VALUE))
    }

    @Test
    fun theReserveIsKeptFree() {
        // Exactly the file's bytes free is not enough: the node's own writes would
        // take the last byte of the volume.
        assertFalse(StorageBudget.fits(3 * gib, 3 * gib))
        assertTrue(StorageBudget.fits(3 * gib + StorageBudget.RESERVE_BYTES, 3 * gib))
        assertFalse(StorageBudget.fits(3 * gib + StorageBudget.RESERVE_BYTES - 1, 3 * gib))
    }

    @Test
    fun anUnknownSizeOrUnknownFreeSpaceDoesNotBlock() {
        // StatFs can fail and a provider can refuse to report a size: a check that
        // cannot be made is not a refusal, and the copy still fails cleanly on a
        // volume that is actually full.
        assertTrue(StorageBudget.fits(-1L, 3 * gib))
        assertTrue(StorageBudget.fits(0L, -1L))
        assertTrue(StorageBudget.fits(-1L, -1L))
        assertTrue(StorageBudget.fits(1 * gib, 0L))
    }

    @Test
    fun theMessageSaysWhatIsNeededWhatIsThereAndWhatToFree() {
        val message = StorageBudget.shortageMessage(3 * gib, 1 * gib, action = "import")
        assertEquals(
            "Not enough free space to import this snapshot: it needs 3.1 GB and the phone has " +
                "1.0 GB free. Free up 2.1 GB and try again.",
            message
        )
    }

    @Test
    fun theLoadActionIsNamedToo() {
        assertTrue(
            StorageBudget.shortageMessage(3 * gib, 1 * gib, action = "load")
                .startsWith("Not enough free space to load this snapshot:")
        )
    }

    @Test
    fun bytesAreShownInTheUnitsTheRestOfTheAppUses() {
        assertEquals("0 B", StorageBudget.humanBytes(0L))
        assertEquals("512 B", StorageBudget.humanBytes(512L))
        assertEquals("1.0 KB", StorageBudget.humanBytes(1024L))
        assertEquals("1.5 MB", StorageBudget.humanBytes(1024L * 1024L * 3 / 2))
        assertEquals("1.0 GB", StorageBudget.humanBytes(gib))
        assertEquals("1.0 TB", StorageBudget.humanBytes(gib * 1024L))
    }
}
