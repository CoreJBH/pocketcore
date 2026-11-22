package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bitcoin.conf the app hands the daemon. */
class NodeConfigTest {

    private val password = "Aa2Bb3Cc4Dd5Ee6Ff7Gg8Hh9Jj2Kk3Ll"

    private fun settings(
        pruneMb: Int = 550,
        network: BitcoinNetwork = BitcoinNetwork.MAINNET,
        torProxy: Boolean = false
    ) = NodeSettingsState(
        network = network,
        pruneMb = pruneMb,
        torProxy = torProxy,
        rpcUser = "abcd1234efgh5678",
        rpcPassword = password
    )

    @Test
    fun writesTheFullChainWithPruneZero() {
        val conf = NodeConfig.content(settings(pruneMb = 0), rpcPort = 8332, p2pPort = 8333)
        assertTrue(conf.lines().contains("prune=0"))
        // Core reads a missing prune just like prune=0, but the file says which
        // one the user picked.
        assertFalse(conf.lines().any { it.startsWith("prune=") && it != "prune=0" })
    }

    @Test
    fun writesTheConfiguredPruneSize() {
        assertTrue(NodeConfig.content(settings(pruneMb = 550), 8332, 8333).lines().contains("prune=550"))
        assertTrue(NodeConfig.content(settings(pruneMb = 5120), 8332, 8333).lines().contains("prune=5120"))
    }

    @Test
    fun keepsThePasswordOutOfTheFile() {
        val conf = NodeConfig.content(settings(), rpcPort = 8332, p2pPort = 8333)
        assertFalse("the plaintext password must never be written", conf.contains(password))
        assertFalse(conf.lines().any { it.startsWith("rpcpassword=") })
        val auth = conf.lines().first { it.startsWith("rpcauth=") }.substringAfter("rpcauth=")
        assertEquals(2, auth.split(':').size)
        assertEquals(2, auth.split('$').size)
        val salt = auth.substringAfter(':').substringBefore('$')
        assertEquals(RpcAuth.hmacHex(salt, password), auth.substringAfter('$'))
    }

    @Test
    fun writesPortsIntoTheNetworkSection() {
        val conf = NodeConfig.content(settings(network = BitcoinNetwork.TESTNET4), rpcPort = 48340, p2pPort = 48341)
        assertTrue(conf.lines().contains("chain=testnet4"))
        assertTrue(conf.lines().contains("[testnet4]"))
        assertFalse(conf.lines().contains("[main]"))
        assertTrue(conf.lines().contains("rpcport=48340"))
        assertTrue(conf.lines().contains("port=48341"))
        // Network-only options must sit below the section header, or Core
        // rejects them for the active network.
        val body = conf.substringAfter("[testnet4]")
        assertTrue(body.lines().contains("rpcbind=127.0.0.1"))
        assertTrue(body.lines().contains("rpcallowip=127.0.0.1"))
        assertTrue(body.lines().contains("rpcport=48340"))
    }

    @Test
    fun mainnetKeepsItsOwnDefaults() {
        val conf = NodeConfig.content(settings(), rpcPort = 8332, p2pPort = 8333)
        assertTrue(conf.lines().contains("chain=main"))
        assertTrue(conf.lines().contains("[main]"))
        assertTrue(conf.lines().contains("rpcbind=127.0.0.1"))
    }

    @Test
    fun writesNoProxyUnlessTheUserTurnedTorOn() {
        val conf = NodeConfig.content(settings(), rpcPort = 8332, p2pPort = 8333)
        assertFalse(conf.lines().any { it.startsWith("proxy=") })
    }

    /**
     * `proxy=` is a global option (Core's own sample bitcoin.conf documents the
     * per-network form as a `=network` suffix on the value), so it must sit above
     * the section header: an option in a section it does not belong to makes Core
     * reject the file.
     */
    @Test
    fun writesTheOrbotProxyForEveryNetwork() {
        for (network in BitcoinNetwork.entries) {
            val conf = NodeConfig.content(settings(network = network, torProxy = true), 8332, 8333)
            assertTrue(conf.lines().contains("proxy=${NodeConfig.TOR_PROXY}"))
            assertEquals("127.0.0.1:9050", NodeConfig.TOR_PROXY)
            val global = conf.substringBefore("[${network.chainArg}]")
            assertTrue(global.lines().contains("proxy=${NodeConfig.TOR_PROXY}"))
        }
    }

    /**
     * A proxy on its own is not privacy. An inbound socket advertises the
     * phone's own address to every peer it meets, and the DNS seeds are resolved
     * by name - in the clear - before a single connection is proxied, which names
     * the phone to its resolver and to anyone watching that path. Both are off
     * whenever Tor is on, and on again when it is off.
     */
    @Test
    fun torRoutingTurnsOffClearnetListeningAndSeedLookups() {
        for (network in BitcoinNetwork.entries) {
            val lines = NodeConfig.content(settings(network = network, torProxy = true), 8332, 8333).lines()
            assertTrue("listen must be off with Tor on", lines.contains("listen=0"))
            assertTrue("dnsseed must be off with Tor on", lines.contains("dnsseed=0"))
            assertFalse(lines.contains("listen=1"))
            assertFalse(lines.contains("dnsseed=1"))
        }
    }

    @Test
    fun directConnectionsStillListenAndUseSeedLookups() {
        val lines = NodeConfig.content(settings(), 8332, 8333).lines()
        assertTrue(lines.contains("listen=1"))
        assertTrue(lines.contains("dnsseed=1"))
        assertFalse(lines.contains("listen=0"))
        assertFalse(lines.contains("dnsseed=0"))
    }
}
