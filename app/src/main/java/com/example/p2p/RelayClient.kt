package com.example.p2p

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Client for the sol-mesh relay (see relay/relay_server.py).
 *
 * Both phones connect OUT to the relay with the same one-time code, so it works behind any NAT,
 * including mobile carrier NAT. The relay pairs them and then forwards raw bytes; the normal
 * authenticated end-to-end encrypted handshake runs on top, so the relay cannot read messages.
 *
 * Pure JVM on purpose (no Android imports) so it can be unit tested on a laptop.
 */
object RelayClient {
    const val DEFAULT_PORT = 9000

    /** A paired connection. [initiator] tells this phone which side of the handshake to play. */
    class Paired(val socket: Socket, val initiator: Boolean)

    // No look-alike characters (0/O, 1/I/L)
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val CODE_REGEX = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** Random one-time code, 12 characters (about 60 bits). */
    fun generateCode(): String {
        val rnd = SecureRandom()
        return (1..12).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    fun isValidCode(code: String): Boolean = CODE_REGEX.matches(code)

    /** Accepts "host", "host:port" or "[ipv6]:port"; the port defaults to [DEFAULT_PORT]. */
    fun parseRelayAddress(input: String): Pair<String, Int>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        NatTraversal.parseHostPort(s)?.let { return it }
        return if (!s.contains(':') && !s.contains('/') && !s.contains(' ')) s to DEFAULT_PORT else null
    }

    /**
     * Connects to the relay, joins [code] and waits until the other phone joins as well.
     * Returns null on timeout or cancellation; throws IOException if the relay is unreachable or refuses.
     */
    fun join(
        host: String,
        port: Int,
        code: String,
        waitMs: Long = 135_000, // a bit longer than the relay's own 120 s wait, so its TIMEOUT reply arrives first
        connectMs: Int = 8000,
        cancelled: () -> Boolean = { false }
    ): Paired? {
        require(isValidCode(code)) { "Invalid code" }
        val socket = Socket()
        var ok = false
        try {
            socket.connect(InetSocketAddress(host, port), connectMs)
            socket.tcpNoDelay = true
            socket.getOutputStream().apply {
                write("JOIN $code\n".toByteArray(Charsets.US_ASCII))
                flush()
            }

            socket.soTimeout = 1000 // so we can notice cancellation / deadline
            val deadline = System.currentTimeMillis() + waitMs
            val line = readLine(socket, deadline, cancelled) ?: return null
            val result = when {
                line == "PAIRED I" -> Paired(socket, initiator = true)
                line == "PAIRED R" -> Paired(socket, initiator = false)
                line == "TIMEOUT" -> null
                line.startsWith("ERR") -> throw IOException("Relay refused: ${line.removePrefix("ERR").trim()}")
                else -> throw IOException("Unexpected reply from relay")
            }
            if (result != null) {
                socket.soTimeout = 0 // the handshake layer manages its own timeouts
                ok = true
            }
            return result
        } finally {
            if (!ok) try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Reads one line byte by byte so no bytes after the newline (peer data) are consumed. */
    private fun readLine(socket: Socket, deadline: Long, cancelled: () -> Boolean): String? {
        val input = socket.getInputStream()
        val sb = StringBuilder()
        while (true) {
            if (cancelled() || System.currentTimeMillis() > deadline) return null
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (b < 0) throw IOException("Relay closed the connection")
            if (b == '\n'.code) return sb.toString().trim()
            sb.append(b.toChar())
            if (sb.length > 128) throw IOException("Unexpected reply from relay")
        }
    }
}
