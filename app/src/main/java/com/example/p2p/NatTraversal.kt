package com.example.p2p

import java.io.DataInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom

/**
 * Best-effort NAT traversal for two phones on different networks, with no relay server.
 *
 * 1. [discoverTcpMapping] asks a public STUN server (over TCP) which public ip:port our NAT
 *    gave to a TCP connection leaving from [PUNCH_PORT].
 * 2. The two users exchange those addresses by hand (copy / paste).
 * 3. [punch] makes both phones connect to each other from [PUNCH_PORT] at the same time
 *    (TCP simultaneous open), which makes both NATs let the connection through.
 *
 * This only works when both NATs keep the same public port for a given local port
 * ("endpoint-independent mapping"). Symmetric NATs, which many mobile carriers use,
 * will not work; [punch] then returns null.
 *
 * Pure JVM on purpose (no Android imports) so it can be unit tested on a laptop.
 */
object NatTraversal {
    /** Fixed local port used for both the STUN query and the punch attempts. */
    const val PUNCH_PORT = 9093

    /** Public STUN servers that answer over TCP (port 3478 is the standard for UDP and TCP). */
    val DEFAULT_STUN_SERVERS: List<Pair<String, Int>> = listOf(
        "stun.stunprotocol.org" to 3478,
        "stun.sipgate.net" to 3478,
        "stun.voip.blackberry.com" to 3478
    )

    private const val MAGIC_COOKIE = 0x2112A442
    private const val TYPE_BINDING_REQUEST = 0x0001
    private const val TYPE_BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private const val MAX_STUN_BODY = 2048

    /**
     * Returns our public "ip:port" as seen by the first STUN server that answers,
     * or null if none did.
     */
    fun discoverTcpMapping(
        servers: List<Pair<String, Int>> = DEFAULT_STUN_SERVERS,
        localPort: Int = PUNCH_PORT,
        timeoutMs: Int = 4000
    ): String? {
        for ((host, port) in servers) {
            try {
                return stunBinding(host, port, localPort, timeoutMs)
            } catch (_: Exception) {
                // try the next server
            }
        }
        return null
    }

    /** Sends one STUN Binding Request over TCP from [localPort] and parses the reply. */
    fun stunBinding(host: String, port: Int, localPort: Int, timeoutMs: Int): String {
        val socket = Socket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localPort))
            socket.soTimeout = timeoutMs
            val target = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: throw IllegalStateException("$host has no IPv4 address")
            socket.connect(InetSocketAddress(target, port), timeoutMs)

            val txn = ByteArray(12).also { SecureRandom().nextBytes(it) }
            socket.getOutputStream().apply {
                write(buildBindingRequest(txn))
                flush()
            }

            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(20)
            input.readFully(header)
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            require(length in 0..MAX_STUN_BODY) { "Bad STUN length $length" }
            val body = ByteArray(length)
            input.readFully(body)
            return parseBindingResponse(header, body, txn)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun buildBindingRequest(txn: ByteArray): ByteArray {
        require(txn.size == 12)
        val msg = ByteArray(20)
        msg[0] = (TYPE_BINDING_REQUEST shr 8).toByte()
        msg[1] = (TYPE_BINDING_REQUEST and 0xFF).toByte()
        // length (2 bytes) stays 0: no attributes
        msg[4] = (MAGIC_COOKIE ushr 24).toByte()
        msg[5] = (MAGIC_COOKIE ushr 16).toByte()
        msg[6] = (MAGIC_COOKIE ushr 8).toByte()
        msg[7] = MAGIC_COOKIE.toByte()
        System.arraycopy(txn, 0, msg, 8, 12)
        return msg
    }

    /** Parses a Binding Success Response and returns "ip:port" (IPv6 as "[ip]:port"). */
    fun parseBindingResponse(header: ByteArray, body: ByteArray, txn: ByteArray): String {
        val type = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        require(type == TYPE_BINDING_SUCCESS) { "Not a binding success response (type=$type)" }
        val cookie = ((header[4].toInt() and 0xFF) shl 24) or ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        require(cookie == MAGIC_COOKIE) { "Bad magic cookie" }
        for (i in 0 until 12) require(header[8 + i] == txn[i]) { "Transaction id mismatch" }

        var xorMapped: String? = null
        var mapped: String? = null
        var pos = 0
        while (pos + 4 <= body.size) {
            val attrType = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            val attrLen = ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)
            val valueStart = pos + 4
            if (valueStart + attrLen > body.size) break
            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> xorMapped = parseAddress(body, valueStart, attrLen, txn, xor = true)
                ATTR_MAPPED_ADDRESS -> mapped = parseAddress(body, valueStart, attrLen, txn, xor = false)
            }
            pos = valueStart + ((attrLen + 3) and 3.inv()) // attributes are padded to 4 bytes
        }
        return xorMapped ?: mapped ?: throw IllegalStateException("No mapped address in STUN response")
    }

    private fun parseAddress(buf: ByteArray, start: Int, len: Int, txn: ByteArray, xor: Boolean): String? {
        if (len < 8) return null
        val family = buf[start + 1].toInt() and 0xFF
        var port = ((buf[start + 2].toInt() and 0xFF) shl 8) or (buf[start + 3].toInt() and 0xFF)
        if (xor) port = port xor (MAGIC_COOKIE ushr 16)

        return when (family) {
            0x01 -> {
                val ip = IntArray(4) { buf[start + 4 + it].toInt() and 0xFF }
                if (xor) {
                    ip[0] = ip[0] xor ((MAGIC_COOKIE ushr 24) and 0xFF)
                    ip[1] = ip[1] xor ((MAGIC_COOKIE ushr 16) and 0xFF)
                    ip[2] = ip[2] xor ((MAGIC_COOKIE ushr 8) and 0xFF)
                    ip[3] = ip[3] xor (MAGIC_COOKIE and 0xFF)
                }
                "${ip[0]}.${ip[1]}.${ip[2]}.${ip[3]}:$port"
            }
            0x02 -> {
                if (len < 20) return null
                val raw = ByteArray(16) { buf[start + 4 + it] }
                if (xor) {
                    val key = ByteArray(16)
                    key[0] = (MAGIC_COOKIE ushr 24).toByte()
                    key[1] = (MAGIC_COOKIE ushr 16).toByte()
                    key[2] = (MAGIC_COOKIE ushr 8).toByte()
                    key[3] = MAGIC_COOKIE.toByte()
                    System.arraycopy(txn, 0, key, 4, 12)
                    for (i in 0 until 16) raw[i] = (raw[i].toInt() xor key[i].toInt()).toByte()
                }
                val groups = (0 until 8).joinToString(":") {
                    (((raw[it * 2].toInt() and 0xFF) shl 8) or (raw[it * 2 + 1].toInt() and 0xFF)).toString(16)
                }
                "[$groups]:$port"
            }
            else -> null
        }
    }

    /**
     * The addresses a phone can be reached at: [v6] (a global IPv6 address; usually no NAT, only a
     * stateful firewall) and [v4] (public IPv4 mapping found with STUN). Either may be null.
     */
    data class Candidates(val v4: String?, val v6: String?) {
        fun isEmpty() = v4 == null && v6 == null

        /** One line the user can copy/paste to the other phone, e.g. "[2001:db8::1]:9093,203.0.113.5:9093". */
        fun toShareCode(): String = listOfNotNull(v6, v4).joinToString(",")
    }

    /** Parses a share code (comma / space / semicolon separated "host:port" entries). */
    fun parseShareCode(code: String): Candidates {
        var v4: String? = null
        var v6: String? = null
        for (token in code.split(',', ';', ' ', '\n', '\t')) {
            val hp = parseHostPort(token) ?: continue
            val formatted = format(hp.first, hp.second)
            if (hp.first.contains(':')) { if (v6 == null) v6 = formatted } else { if (v4 == null) v4 = formatted }
        }
        return Candidates(v4, v6)
    }

    /** Global (publicly routable) IPv6 addresses of this device, without zone ids. */
    fun globalIpv6Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet6Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                        addr.isMulticastAddress || addr.isAnyLocalAddress
                    ) continue
                    if ((addr.address[0].toInt() and 0xFE) == 0xFC) continue // unique-local fc00::/7
                    result.add((addr.hostAddress ?: continue).substringBefore('%'))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Finds both our IPv6 address (if any) and our IPv4 STUN mapping (if any). */
    fun discoverCandidates(): Candidates {
        val v6 = globalIpv6Addresses().firstOrNull()?.let { format(it, PUNCH_PORT) }
        val v4 = discoverTcpMapping()
        return Candidates(v4 = v4?.takeIf { !it.startsWith("[") }, v6 = v6)
    }

    /**
     * Picks the address family both phones share: IPv6 if both have it, otherwise IPv4.
     * Both phones evaluate this with the roles swapped and always agree. Returns (mine, theirs) or null.
     */
    fun choosePair(mine: Candidates, theirs: Candidates): Pair<String, String>? = when {
        mine.v6 != null && theirs.v6 != null -> mine.v6 to theirs.v6
        mine.v4 != null && theirs.v4 != null -> mine.v4 to theirs.v4
        else -> null
    }

    /**
     * Splits "ip:port", "host:port" or "[ipv6]:port" into host and port. Returns null if invalid.
     */
    fun parseHostPort(input: String): Pair<String, Int>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val host: String
        val portStr: String
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0 || end + 2 > s.length || s.getOrNull(end + 1) != ':') return null
            host = s.substring(1, end)
            portStr = s.substring(end + 2)
        } else {
            val idx = s.lastIndexOf(':')
            if (idx <= 0 || s.indexOf(':') != idx) return null // plain host:port only
            host = s.substring(0, idx)
            portStr = s.substring(idx + 1)
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    /** Canonical "host:port" string, used so both phones order the two addresses identically. */
    fun format(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /**
     * Repeatedly connects to the remote public address from [localPort] until a connection
     * is established or [totalMs] elapsed. Both phones must run this at about the same time.
     * Returns the connected socket, or null if no direct path was found.
     */
    fun punch(
        remoteHost: String,
        remotePort: Int,
        localPort: Int = PUNCH_PORT,
        totalMs: Long = 30_000,
        attemptMs: Int = 1500,
        cancelled: () -> Boolean = { false },
        localAddress: String? = null
    ): Socket? {
        val deadline = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            val socket = Socket()
            try {
                socket.reuseAddress = true
                if (localAddress != null) {
                    socket.bind(InetSocketAddress(InetAddress.getByName(localAddress), localPort))
                } else {
                    socket.bind(InetSocketAddress(localPort))
                }
                socket.connect(InetSocketAddress(remoteHost, remotePort), attemptMs)
                socket.tcpNoDelay = true
                return socket
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
                try { Thread.sleep(250) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }
}
