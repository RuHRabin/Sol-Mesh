package com.example.p2p

import java.net.InetSocketAddress

/**
 * Libp2p-compatible Multiaddress parser and representation.
 * Supports:
 *  - /ip4/192.168.1.50/tcp/9091
 *  - /ip4/192.168.1.50/udp/9090
 *  - /dns4/relay.sol-mesh.org/tcp/9091
 *  - 192.168.1.50:9091 (standard socket format)
 *  - 192.168.1.50 (defaults to port 9091)
 */
data class Multiaddress(
    val host: String,
    val port: Int,
    val transport: TransportType = TransportType.TCP
) {
    enum class TransportType { TCP, UDP }

    fun toSocketAddress(): InetSocketAddress = InetSocketAddress(host, port)

    override fun toString(): String {
        val proto = if (transport == TransportType.TCP) "tcp" else "udp"
        val ipType = if (host.contains(":")) "ip6" else "ip4"
        return "/$ipType/$host/$proto/$port"
    }

    companion object {
        fun fromString(raw: String): Multiaddress {
            val clean = raw.trim()
            if (clean.isEmpty()) return Multiaddress("127.0.0.1", 9091, TransportType.TCP)

            // If input is standard host:port or plain IP without leading slash
            if (!clean.startsWith("/")) {
                // [ipv6]:port
                if (clean.startsWith("[")) {
                    val end = clean.indexOf(']')
                    if (end > 1) {
                        val h = clean.substring(1, end)
                        val p = clean.substring(end + 1).removePrefix(":").trim().toIntOrNull() ?: 9091
                        return Multiaddress(h, p, TransportType.TCP)
                    }
                }
                // bare IPv6 address (more than one colon, no brackets): default port
                if (clean.count { it == ':' } > 1) {
                    return Multiaddress(clean, 9091, TransportType.TCP)
                }
                if (clean.contains(":")) {
                    val split = clean.split(":")
                    val h = split[0].trim()
                    val p = split[1].toIntOrNull() ?: 9091
                    return Multiaddress(if (h.isEmpty()) "127.0.0.1" else h, p, TransportType.TCP)
                } else {
                    return Multiaddress(clean, 9091, TransportType.TCP)
                }
            }

            val parts = clean.split("/").filter { it.isNotEmpty() }
            var host = "127.0.0.1"
            var port = 9091
            var transport = TransportType.TCP

            var i = 0
            while (i < parts.size) {
                when (parts[i].lowercase()) {
                    "ip4", "ip6", "dns4", "dns" -> {
                        if (i + 1 < parts.size) {
                            host = parts[i + 1]
                            i++
                        }
                    }
                    "tcp" -> {
                        transport = TransportType.TCP
                        if (i + 1 < parts.size) {
                            port = parts[i + 1].toIntOrNull() ?: port
                            i++
                        }
                    }
                    "udp", "quic" -> {
                        transport = TransportType.UDP
                        if (i + 1 < parts.size) {
                            port = parts[i + 1].toIntOrNull() ?: port
                            i++
                        }
                    }
                }
                i++
            }
            return Multiaddress(host, port, transport)
        }
    }
}
