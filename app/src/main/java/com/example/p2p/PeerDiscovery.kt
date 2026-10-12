package com.example.p2p

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.crypto.IdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Decentralized peer discovery engine.
 * Combines:
 * 1. Directed Subnet Broadcast across all active network interfaces (Wi-Fi, Hotspot, LAN)
 * 2. Fallback IPv4 global broadcast (255.255.255.255) and Multicast
 * 3. Kademlia DHT periodic announcement and lookup
 */
class PeerDiscovery(
    private val context: Context,
    private val identityManager: IdentityManager,
    private val onPeerDiscovered: (PeerRecord) -> Unit
) {
    companion object {
        private const val TAG = "PeerDiscovery"
        const val DISCOVERY_PORT = 9090
        const val MULTICAST_GROUP = "239.255.255.250"
    }

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var running = false
    private var discoveryScope: CoroutineScope? = null

    private var currentAddresses: List<String> = emptyList()

    fun updateLocalAddresses(addresses: List<String>) {
        currentAddresses = addresses
    }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        discoveryScope = scope

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("SolMeshP2PMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        // Listener coroutine
        listenJob = scope.launch(Dispatchers.IO) {
            runListener()
        }

        // Periodic announcement coroutine
        broadcastJob = scope.launch(Dispatchers.IO) {
            runBroadcaster()
        }
    }

    /**
     * Trigger immediate broadcast burst across all network interfaces.
     */
    fun broadcastNow() {
        discoveryScope?.launch(Dispatchers.IO) {
            sendBroadcastWave()
        }
    }

    private suspend fun runListener() {
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                broadcast = true
                try {
                    joinGroup(InetAddress.getByName(MULTICAST_GROUP))
                } catch (e: Exception) {
                    Log.d(TAG, "Join multicast group warning: ${e.message}")
                }
            }
            val buffer = ByteArray(4096)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val rawStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val record = PeerRecord.fromJson(rawStr)
                    if (record != null && record.peerId != identityManager.peerId) {
                        if (record.verifySignature() && !record.isExpired()) {
                            // Inject observed socket packet host into addresses if missing
                            val senderHost = packet.address?.hostAddress ?: ""
                            val updatedAddrs = record.addresses.toMutableList()
                            if (senderHost.isNotEmpty() && !senderHost.startsWith("127.")) {
                                val observedAddr = "/ip4/$senderHost/tcp/9091"
                                if (!updatedAddrs.contains(observedAddr)) {
                                    updatedAddrs.add(0, observedAddr) // Prioritize observed interface
                                }
                            }
                            onPeerDiscovered(record.copy(addresses = updatedAddrs))
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery listener error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun runBroadcaster() {
        // Initial burst: 3 announcements spaced 1 second apart
        repeat(3) {
            if (!running) return
            sendBroadcastWave()
            delay(1000)
        }

        while (running) {
            sendBroadcastWave()
            delay(5000) // Announce every 5 seconds for fast discovery
        }
    }

    private fun sendBroadcastWave() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }

            // Ensure we include fresh local IPs in addresses
            val freshAddresses = currentAddresses.toMutableList()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            addr.hostAddress?.let { ip ->
                                val ma = "/ip4/$ip/tcp/9091"
                                if (!freshAddresses.contains(ma)) {
                                    freshAddresses.add(ma)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            val record = PeerRecord.createSigned(
                identityManager = identityManager,
                addresses = freshAddresses,
                ttlSeconds = 120
            )
            val bytes = record.toJson().toByteArray(Charsets.UTF_8)
            val targetAddresses = getBroadcastTargets()

            for (targetAddr in targetAddresses) {
                try {
                    val packet = DatagramPacket(bytes, bytes.size, targetAddr, DISCOVERY_PORT)
                    socket.send(packet)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "Broadcast tick error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun getBroadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            // Global IPv4 broadcast
            targets.add(InetAddress.getByName("255.255.255.255"))
            // Multicast group
            targets.add(InetAddress.getByName(MULTICAST_GROUP))

            // Subnet directed broadcasts for every interface (hotspot ap0, wlan0, rndis0, etc.)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val bcast = ifaceAddr.broadcast
                    if (bcast != null && !targets.contains(bcast)) {
                        targets.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resolving broadcast targets: ${e.message}")
        }
        return targets
    }

    fun stop() {
        running = false
        broadcastJob?.cancel()
        listenJob?.cancel()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
    }
}
