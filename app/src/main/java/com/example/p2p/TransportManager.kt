package com.example.p2p

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * P2P Transport Manager.
 * Operates the direct listening ServerSocket on the device,
 * detects local and network interface IP addresses, and routes
 * accepted inbound sockets to ConnectionManager.
 */
class TransportManager(
    val requestedPort: Int = 9091,
    private val onInboundConnection: (Socket) -> Unit
) {
    companion object {
        private const val TAG = "TransportManager"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    var listeningPort: Int = requestedPort
        private set

    var isListening: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (isListening) return

        try {
            serverSocket = ServerSocket(requestedPort).apply {
                reuseAddress = true
            }
            listeningPort = serverSocket!!.localPort
        } catch (e: Exception) {
            Log.w(TAG, "Port $requestedPort unavailable, selecting ephemeral port: ${e.message}")
            serverSocket = ServerSocket(0).apply {
                reuseAddress = true
            }
            listeningPort = serverSocket!!.localPort
        }

        isListening = true
        acceptJob = scope.launch(Dispatchers.IO) {
            val srv = serverSocket ?: return@launch
            Log.i(TAG, "P2P Transport listening on port $listeningPort")
            while (isActive && isListening) {
                try {
                    val clientSocket = srv.accept()
                    clientSocket.tcpNoDelay = true
                    clientSocket.soTimeout = 0 // Keep-alive socket
                    onInboundConnection(clientSocket)
                } catch (e: Exception) {
                    if (!isListening) break
                    Log.d(TAG, "Server socket accept tick error: ${e.message}")
                }
            }
        }
    }

    /**
     * Enumerates non-loopback IPv4 addresses of local network interfaces.
     */
    fun getLocalIpAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { addresses.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces: ${e.message}")
        }
        return addresses
    }

    fun getLocalMultiaddresses(): List<String> {
        val ips = getLocalIpAddresses()
        if (ips.isEmpty()) {
            return listOf("/ip4/127.0.0.1/tcp/$listeningPort")
        }
        return ips.map { "/ip4/$it/tcp/$listeningPort" }
    }

    fun stop() {
        isListening = false
        acceptJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }
}
