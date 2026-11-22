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
    fun activatingASnapshotNeedsRoomForTheSecondChainstate() {
        // The file is already on disk when LOAD is pressed, so its size is not
        // what the load writes: Core writes a second chainstate beside the one
        // that is already there and keeps both until validation catches up
        // (versionCode 21).
        val snapshot = 9 * gib
        val chainstate = 13 * gib
        assertEquals(13 * gib + StorageBudget.RESERVE_BYTES, StorageBudget.requiredLoadBytes(snapshot, chainstate))
        // A phone whose chainstate is smaller than the file is estimated from the
        // file, because the new chainstate holds the same coins.
        assertEquals(9 * gib + StorageBudget.RESERVE_BYTES, StorageBudget.requiredLoadBytes(snapshot, 4 * gib))
        // An unknown figure is not a reason to under-count the known one.
        assertEquals(9 * gib + StorageBudget.RESERVE_BYTES, StorageBudget.requiredLoadBytes(snapshot, -1L))
        assertEquals(13 * gib + StorageBudget.RESERVE_BYTES, StorageBudget.requiredLoadBytes(-1L, chainstate))
        assertEquals(0L, StorageBudget.requiredLoadBytes(-1L, -1L))
    }

    @Test
    fun theLoadRefusesWhenOnlyTheSecondChainstateWouldFit() {
        val snapshot = 9 * gib
        val chainstate = 13 * gib
        // Free space that would have satisfied the old rule (the file's size plus
        // the reserve) is no longer enough.
        assertFalse(StorageBudget.fitsLoad(9 * gib + StorageBudget.RESERVE_BYTES, snapshot, chainstate))
        assertTrue(StorageBudget.fitsLoad(13 * gib + StorageBudget.RESERVE_BYTES, snapshot, chainstate))
        // Unknown free space still never blocks, and neither does an unknown pair.
        assertTrue(StorageBudget.fitsLoad(-1L, snapshot, chainstate))
        assertTrue(StorageBudget.fitsLoad(1 * gib, -1L, -1L))
    }

    @Test
    fun theLoadMessageNamesBothChainstates() {
        val message = StorageBudget.loadShortageMessage(9 * gib, 13 * gib, 5 * gib)
        assertTrue(message.startsWith("Not enough free space to activate this snapshot:"))
        assertTrue(message.contains("second chainstate of about 13.0 GB"))
        assertTrue(message.contains("13.0 GB chainstate already on the phone"))
        assertTrue(message.contains("needs 13.1 GB free"))
        assertTrue(message.contains("the phone has 5.0 GB"))
        assertTrue(message.contains("Free up 8.1 GB and try again."))
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
