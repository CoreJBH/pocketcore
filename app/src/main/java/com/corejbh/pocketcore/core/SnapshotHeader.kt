package com.corejbh.pocketcore.core

import java.io.InputStream

/**
 * The header at the start of every Bitcoin Core UTXO snapshot file.
 *
 * The layout is the one `node::SnapshotMetadata::Serialize` writes in the
 * daemon this app ships (`src/node/utxo_snapshot.h`, Bitcoin Core v32.1):
 *
 * | bytes | field |
 * | --- | --- |
 * | 5 | magic `u t x o 0xFF` (`SNAPSHOT_MAGIC_BYTES`) |
 * | 2 | format version, little-endian (`VERSION`, 2 in v32.1) |
 * | 4 | the network's message start (`pchMessageStart`, e.g. `F9 BE B4 D9`) |
 * | 32 | the base block hash (`m_base_blockhash`) |
 * | 8 | the coin count (`m_coins_count`), little-endian |
 *
 * `loadtxoutset` replays the rest of the file as a whole chainstate, and it
 * refuses a file whose header it does not recognise - but only after the daemon
 * has been handed a path and has started reading. A file picked in the system
 * picker can be anything (a screenshot, an archive, a snapshot of another
 * network), and importing nothing but its size meant the app copied gigabytes
 * of the wrong file and reported the mistake from inside Core, or worse, called
 * `loadtxoutset` on it. The header is therefore checked here, on the phone,
 * before a byte of the copy is written (versionCode 21).
 *
 * The check is deliberately exact about the version: the daemon accepts only
 * the versions compiled into it, and refusing an older file here names the
 * reason instead of leaving Core's "Version of snapshot N does not match any of
 * the supported versions" for a reader to decode.
 *
 * Plain Kotlin over a [ByteArray], so every refusal is unit tested
 * (`SnapshotHeaderTest`).
 */
internal object SnapshotHeader {

    /** `SNAPSHOT_MAGIC_BYTES` = {'u', 't', 'x', 'o', 0xff}. */
    private val MAGIC = byteArrayOf(0x75, 0x74, 0x78, 0x6F, 0xFF.toByte())

    /** The only format version Bitcoin Core 32.1 writes and accepts. */
    const val VERSION = 2

    /** Size of the header: 5 + 2 + 4 + 32 + 8. */
    const val LENGTH = 5 + 2 + 4 + 32 + 8

    /** The network's `pchMessageStart` (`src/kernel/chainparams.cpp`, v32.1). */
    fun networkMagic(network: BitcoinNetwork): ByteArray = when (network) {
        BitcoinNetwork.MAINNET -> byteArrayOf(0xF9.toByte(), 0xBE.toByte(), 0xB4.toByte(), 0xD9.toByte())
        BitcoinNetwork.SIGNET -> byteArrayOf(0x0A, 0x03, 0xCF.toByte(), 0x40)
        BitcoinNetwork.TESTNET4 -> byteArrayOf(0x1C, 0x16, 0x3F, 0x28)
    }

    /** Networks whose magic can appear in a file, for a message that names it. */
    private val KNOWN_MAGIC: List<Pair<ByteArray, String>> = listOf(
        networkMagic(BitcoinNetwork.MAINNET) to "mainnet",
        networkMagic(BitcoinNetwork.SIGNET) to "signet",
        networkMagic(BitcoinNetwork.TESTNET4) to "testnet4",
        byteArrayOf(0x0B, 0x11, 0x09, 0x07) to "testnet3",
        byteArrayOf(0xFA.toByte(), 0xBF.toByte(), 0xB5.toByte(), 0xDA.toByte()) to "regtest"
    )

    /**
     * Reads at most [LENGTH] bytes from [stream], taking as much as the stream
     * gives in one or more reads. The stream is not closed here.
     */
    fun read(stream: InputStream): ByteArray {
        val buffer = ByteArray(LENGTH)
        var total = 0
        while (total < LENGTH) {
            val read = try {
                stream.read(buffer, total, LENGTH - total)
            } catch (error: java.io.IOException) {
                -1
            }
            if (read <= 0) break
            total += read
        }
        return if (total == LENGTH) buffer else buffer.copyOf(total)
    }

    /**
     * Why [header] is not a snapshot this daemon can load on [network], or null
     * when it is.
     *
     * A header shorter than [LENGTH] is refused rather than padded: a file that
     * does not even carry the fields Core validates is not one Core wrote.
     */
    fun problem(header: ByteArray, network: BitcoinNetwork): String? {
        if (header.size < LENGTH) {
            return "This file is too small to be a Bitcoin Core UTXO snapshot: its header alone is " +
                "$LENGTH bytes, and the file has ${header.size}."
        }
        if (!MAGIC.indices.all { header[it] == MAGIC[it] }) {
            return "This file does not start with Bitcoin Core's snapshot magic bytes (utxo 0xFF), " +
                "so it is not a UTXO snapshot. Choose a .dat file written by dumptxoutset."
        }
        val version = (header[5].toInt() and 0xFF) or ((header[6].toInt() and 0xFF) shl 8)
        if (version != VERSION) {
            return "This snapshot is format version $version, and the bundled Bitcoin Core 32.1 " +
                "writes and loads version $VERSION only. Dump a new snapshot with dumptxoutset."
        }
        val magic = header.copyOfRange(7, 11)
        val fileNetwork = KNOWN_MAGIC.firstOrNull { (known, _) -> known.indices.all { magic[it] == known[it] } }
        if (fileNetwork == null) {
            return "This snapshot was written for a network this daemon does not know (network magic " +
                "${hex(magic)}). Choose a file for ${network.label}."
        }
        if (!fileNetwork.first.contentEquals(networkMagic(network))) {
            return "This snapshot was written for ${fileNetwork.second}, and this node runs " +
                "${network.label}. Switch the network in SETTINGS, or choose a ${network.label} snapshot."
        }
        val baseHash = header.copyOfRange(11, 43)
        if (baseHash.all { it == 0.toByte() }) {
            return "This snapshot's header names no base block (an all-zero hash), so it is damaged " +
                "or truncated. Dump a new snapshot."
        }
        val coins = (0 until 8).fold(0L) { value, index ->
            value or ((header[43 + index].toLong() and 0xFF) shl (8 * index))
        }
        if (coins == 0L) {
            return "This snapshot's header says it holds no coins, so it is damaged or truncated. " +
                "Dump a new snapshot."
        }
        return null
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
