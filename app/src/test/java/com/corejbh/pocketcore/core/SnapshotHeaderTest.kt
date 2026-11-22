package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header check an import and a load make before Core is asked anything
 * (versionCode 21).
 *
 * The bytes are built the way `node::SnapshotMetadata::Serialize` writes them in
 * Bitcoin Core v32.1 (`src/node/utxo_snapshot.h`): the 5 magic bytes, a
 * little-endian version, the network message start, the base block hash and the
 * coin count.
 */
class SnapshotHeaderTest {

    private fun header(
        magic: ByteArray = byteArrayOf(0x75, 0x74, 0x78, 0x6F, 0xFF.toByte()),
        version: Int = 2,
        networkMagic: ByteArray = SnapshotHeader.networkMagic(BitcoinNetwork.MAINNET),
        baseHash: ByteArray = ByteArray(32) { (it + 1).toByte() },
        coins: Long = 164_241_311L,
        size: Int = SnapshotHeader.LENGTH
    ): ByteArray {
        val bytes = ByteArray(SnapshotHeader.LENGTH)
        magic.copyInto(bytes, 0)
        bytes[5] = (version and 0xFF).toByte()
        bytes[6] = ((version shr 8) and 0xFF).toByte()
        networkMagic.copyInto(bytes, 7)
        baseHash.copyInto(bytes, 11)
        for (index in 0 until 8) {
            bytes[43 + index] = ((coins shr (8 * index)) and 0xFF).toByte()
        }
        return if (size == SnapshotHeader.LENGTH) bytes else bytes.copyOf(size)
    }

    @Test
    fun aMainnetHeaderIsAccepted() {
        assertNull(SnapshotHeader.problem(header(), BitcoinNetwork.MAINNET))
    }

    @Test
    fun aSmallSnapshotOfAnotherNetworkIsAccepted() {
        // testnet4 snapshots are under a gigabyte; the coin count is little-endian.
        assertNull(
            SnapshotHeader.problem(
                header(
                    networkMagic = SnapshotHeader.networkMagic(BitcoinNetwork.TESTNET4),
                    coins = 1L
                ),
                BitcoinNetwork.TESTNET4
            )
        )
        assertNull(
            SnapshotHeader.problem(
                header(
                    networkMagic = SnapshotHeader.networkMagic(BitcoinNetwork.SIGNET),
                    coins = 33_000_000L
                ),
                BitcoinNetwork.SIGNET
            )
        )
    }

    @Test
    fun aFileThatIsNotASnapshotIsRefused() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(SnapshotHeader.LENGTH)
        assertNotNull(SnapshotHeader.problem(png, BitcoinNetwork.MAINNET))
        assertTrue(
            SnapshotHeader.problem(png, BitcoinNetwork.MAINNET)!!.contains("magic bytes")
        )
    }

    @Test
    fun aTruncatedFileIsRefusedByItsOwnSize() {
        val short = header(size = 8)
        val message = SnapshotHeader.problem(short, BitcoinNetwork.MAINNET)
        assertNotNull(message)
        assertTrue(message!!.contains("too small"))
        // The old rule - 1 KB - left a short file to fail inside Core.
        assertNotNull(SnapshotHeader.problem(ByteArray(1023), BitcoinNetwork.MAINNET))
    }

    @Test
    fun anOlderFormatVersionIsRefused() {
        val message = SnapshotHeader.problem(header(version = 1), BitcoinNetwork.MAINNET)
        assertNotNull(message)
        assertTrue(message!!.contains("format version 1"))
        assertTrue(message.contains("version 2"))
    }

    @Test
    fun aSnapshotOfAnotherNetworkIsRefusedAndNamesBoth() {
        val message = SnapshotHeader.problem(
            header(networkMagic = SnapshotHeader.networkMagic(BitcoinNetwork.TESTNET4)),
            BitcoinNetwork.MAINNET
        )
        assertNotNull(message)
        assertTrue(message!!.contains("testnet4"))
        assertTrue(message.contains("Mainnet"))
        // testnet3 is not one of this app's networks, but it is still named.
        val testnet3 = SnapshotHeader.problem(
            header(networkMagic = byteArrayOf(0x0B, 0x11, 0x09, 0x07)),
            BitcoinNetwork.MAINNET
        )
        assertTrue(testnet3!!.contains("testnet3"))
    }

    @Test
    fun anUnknownNetworkMagicIsRefused() {
        val message = SnapshotHeader.problem(
            header(networkMagic = byteArrayOf(0x01, 0x02, 0x03, 0x04)),
            BitcoinNetwork.MAINNET
        )
        assertNotNull(message)
        assertTrue(message!!.contains("does not know"))
    }

    @Test
    fun aDamagedBaseHashOrCoinCountIsRefused() {
        assertTrue(
            SnapshotHeader.problem(header(baseHash = ByteArray(32)), BitcoinNetwork.MAINNET)!!
                .contains("no base block")
        )
        assertTrue(
            SnapshotHeader.problem(header(coins = 0L), BitcoinNetwork.MAINNET)!!
                .contains("no coins")
        )
    }

    @Test
    fun theReaderTakesWhatTheStreamGives() {
        val bytes = header()
        // A single read of a ByteArrayInputStream returns everything; a reader
        // over it stops at the header's length, leaving the coins unread.
        val oneByte = object : java.io.InputStream() {
            var index = 0
            override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index >= bytes.size) return -1
                buffer[offset] = bytes[index++]
                return 1
            }
        }
        val read = SnapshotHeader.read(oneByte)
        assertEquals(SnapshotHeader.LENGTH, read.size)
        assertNull(SnapshotHeader.problem(read, BitcoinNetwork.MAINNET))
        // An empty stream gives back fewer bytes than the header, and that is a
        // refusal rather than a crash.
        assertEquals(0, SnapshotHeader.read(java.io.ByteArrayInputStream(ByteArray(0))).size)
        assertNotNull(SnapshotHeader.problem(ByteArray(0), BitcoinNetwork.MAINNET))
    }
}
