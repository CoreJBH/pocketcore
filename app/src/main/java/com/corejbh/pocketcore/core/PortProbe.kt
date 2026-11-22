package com.corejbh.pocketcore.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Loopback port checks used before a port is handed to bitcoind.
 *
 * The daemon aborts the whole startup when `InitHTTPServer()` cannot bind its
 * RPC endpoint, so the app has to know whether the port is usable before it
 * launches the process. A phone that already runs another node (or an orphan
 * bitcoind from a killed app process) keeps 8332 busy, which is exactly how
 * "Unable to bind to 127.0.0.1:8332 on this computer" happens.
 */
object PortProbe {

    private val loopbackV4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /**
     * The wildcard address, `0.0.0.0`.
     *
     * A port held on every address is a port the daemon cannot have at 127.0.0.1
     * either, and a loopback bind alone does not always say so: SO_REUSEADDR and
     * a dual-stack socket can let one claim succeed while the port is not
     * actually free. Checking the wildcard address as well closes that hole
     * (versionCode 20).
     */
    private val wildcardV4: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))

    /** A loopback socket that holds [port] until it is closed. */
    class Reservation internal constructor(
        private val socket: ServerSocket,
        val port: Int
    ) : AutoCloseable {
        /** Gives the port back, so the daemon can bind it. */
        override fun close() {
            runCatching { socket.close() }
        }
    }

    /**
     * Claims [port] on 127.0.0.1 and keeps it until the reservation is closed.
     *
     * Checking a port and handing it to bitcoind are two separate steps, and the
     * daemon has to bind the endpoint itself, so a reservation cannot stay open
     * across the start. It is held while the configuration is written and closed
     * immediately before the launch, which narrows the window another process
     * could win from a few file writes to a single process start. The credential
     * pair is replaced for every start as well, so a port won in that window
     * never sees a usable password.
     */
    fun reserve(port: Int): Reservation? {
        if (port !in MIN_PORT..MAX_PORT) return null
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(loopbackV4, port), 1)
            Reservation(socket, port)
        }.getOrNull()
    }

    /** [reserve] for the wildcard address, `0.0.0.0`. */
    fun reserveWildcard(port: Int): Reservation? {
        if (port !in MIN_PORT..MAX_PORT) return null
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(wildcardV4, port), 1)
            Reservation(socket, port)
        }.getOrNull()
    }

    /** True when nothing else on the device holds [port] on any local address. */
    fun isFree(port: Int): Boolean {
        if (port !in MIN_PORT..MAX_PORT) return false
        val reservation = reserve(port) ?: return false
        // The reservation has to be given back before the server is asked
        // whether it answers: a bound socket accepts connections even when
        // nobody accepts them, so the probe would find itself.
        reservation.close()
        // A listener that accepted nothing still refuses bind(), but a socket
        // that is bound without listening accepts a connection. Treat a
        // successful connect as "in use" as well.
        if (isListening(port)) return false
        // The same port on every address: a wildcard listener belongs to another
        // app just as much as a loopback one does, and bitcoind's own bind would
        // fail on it.
        val wildcard = reserveWildcard(port) ?: return false
        wildcard.close()
        return true
    }

    /** True when something accepts TCP connections on 127.0.0.1:[port]. */
    fun isListening(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopbackV4, port), 250)
        }
        true
    }.getOrDefault(false)

    /**
     * First free port at or after [preferred], skipping [avoid] (the P2P port,
     * for example).
     *
     * Null when nothing in the window [attempts] ports wide is free: the caller
     * must fail with a clear diagnostic instead of quietly handing bitcoind a
     * port another app holds (versionCode 20). [probe] is the free-port check, so
     * the exhausted case is unit tested without opening that many sockets.
     */
    fun findFree(
        preferred: Int,
        avoid: Set<Int> = emptySet(),
        attempts: Int = 40,
        probe: (Int) -> Boolean = ::isFree
    ): Int? {
        if (preferred !in avoid && probe(preferred)) return preferred
        var candidate = preferred + 1
        val end = preferred + attempts
        while (candidate <= end && candidate <= MAX_PORT) {
            if (candidate !in avoid && probe(candidate)) return candidate
            candidate++
        }
        return null
    }

    /** Waits until [port] is free, polling every 150 ms. */
    fun awaitFree(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isFree(port)) return true
            Thread.sleep(150)
        }
        return isFree(port)
    }

    /**
     * A P2P listen port together with the loopback "onion" port Core binds one
     * above it (127.0.0.1:p2p+1): both have to be free for a clean start.
     *
     * Null when no such pair exists in the window, so the caller reports it.
     */
    fun findFreeListenPair(
        preferred: Int,
        avoid: Set<Int> = emptySet(),
        attempts: Int = 40,
        probe: (Int) -> Boolean = ::isFree
    ): Int? {
        var candidate = preferred
        val end = preferred + attempts
        while (candidate <= end && candidate + 1 <= MAX_PORT) {
            if (candidate !in avoid && (candidate + 1) !in avoid &&
                probe(candidate) && probe(candidate + 1)
            ) {
                return candidate
            }
            candidate++
        }
        return null
    }

    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
}
