package com.example.p2p

import android.util.Log
import com.example.crypto.IdentityManager
import com.example.protocol.DhtEnvelope
import com.example.protocol.DhtRpcType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Kademlia DHT Manager implementing decentralized presence storage,
 * iterative lookup, routing table maintenance, and STUN-like reflex address discovery.
 */
class DhtManager(
    val localPeerId: String,
    private val identityManager: IdentityManager,
    val dhtPort: Int = 9092
) {
    companion object {
        private const val TAG = "DhtManager"

        // Default well-known decentralized bootstrap community node addresses
        val DEFAULT_BOOTSTRAP_NODES = listOf(
            "/ip4/149.28.240.231/udp/9092",
            "/ip4/198.199.120.155/udp/9092",
            "/ip4/45.32.228.10/udp/9092"
        )
    }

    val routingTable = DhtRoutingTable(localPeerId)
    private val valueStore = ConcurrentHashMap<String, PeerRecord>()
    private var dhtSocket: DatagramSocket? = null
    private var workerJob: Job? = null
    private var running = false

    var observedPublicAddress: String? = null
        private set

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true

        try {
            dhtSocket = DatagramSocket(dhtPort).apply {
                soTimeout = 3000
                reuseAddress = true
            }
        } catch (e: Exception) {
            // If primary port is taken, fallback to ephemeral port
            dhtSocket = DatagramSocket().apply { soTimeout = 3000 }
        }

        workerJob = scope.launch(Dispatchers.IO) {
            runDhtServer()
        }

        // Periodic maintenance and republish
        scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                republishPresence()
                delay(60_000)
            }
        }
    }

    private fun runDhtServer() {
        val socket = dhtSocket ?: return
        val buffer = ByteArray(8192)

        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val req = DhtEnvelope.fromJson(jsonStr) ?: continue

                // Update sender in routing table
                val senderAddr = "/ip4/${packet.address.hostAddress}/udp/${packet.port}"
                routingTable.update(
                    DhtRoutingTable.Contact(
                        peerId = req.senderPeerId,
                        multiaddresses = listOf(senderAddr)
                    )
                )

                handleIncomingRpc(req, packet.address, packet.port)
            } catch (_: SocketTimeoutException) {
                // Normal timeout, continue loop
            } catch (e: Exception) {
                if (!running) break
                Log.d(TAG, "DHT server tick error: ${e.message}")
            }
        }
    }

    private fun handleIncomingRpc(req: DhtEnvelope, remoteAddr: InetAddress, remotePort: Int) {
        val observed = "/ip4/${remoteAddr.hostAddress}/udp/$remotePort"

        when (req.type) {
            DhtRpcType.PING -> {
                // Reply with PONG and caller's observed address (STUN functionality)
                val pong = DhtEnvelope(
                    rpcId = req.rpcId,
                    type = DhtRpcType.PONG,
                    senderPeerId = localPeerId,
                    observedAddress = observed
                )
                sendRpc(pong, remoteAddr, remotePort)
            }
            DhtRpcType.PONG -> {
                if (req.observedAddress != null) {
                    observedPublicAddress = req.observedAddress
                }
            }
            DhtRpcType.FIND_NODE -> {
                val target = req.targetPeerId ?: req.senderPeerId
                val closest = routingTable.getClosestNodes(target, 8).map {
                    DhtEnvelope.DhtNodeItem(it.peerId, it.multiaddresses)
                }
                val resp = DhtEnvelope(
                    rpcId = req.rpcId,
                    type = DhtRpcType.NODES_RESPONSE,
                    senderPeerId = localPeerId,
                    nodes = closest,
                    observedAddress = observed
                )
                sendRpc(resp, remoteAddr, remotePort)
            }
            DhtRpcType.PUT_VALUE -> {
                val record = req.peerRecord
                if (record != null && record.verifySignature() && !record.isExpired()) {
                    valueStore[record.peerId] = record
                }
            }
            DhtRpcType.GET_VALUE -> {
                val target = req.targetPeerId ?: ""
                val record = valueStore[target]
                if (record != null && !record.isExpired()) {
                    val resp = DhtEnvelope(
                        rpcId = req.rpcId,
                        type = DhtRpcType.VALUE_RESPONSE,
                        senderPeerId = localPeerId,
                        peerRecord = record,
                        observedAddress = observed
                    )
                    sendRpc(resp, remoteAddr, remotePort)
                } else {
                    val closest = routingTable.getClosestNodes(target, 8).map {
                        DhtEnvelope.DhtNodeItem(it.peerId, it.multiaddresses)
                    }
                    val resp = DhtEnvelope(
                        rpcId = req.rpcId,
                        type = DhtRpcType.NODES_RESPONSE,
                        senderPeerId = localPeerId,
                        nodes = closest,
                        observedAddress = observed
                    )
                    sendRpc(resp, remoteAddr, remotePort)
                }
            }
            else -> {}
        }
    }

    fun publishRecord(record: PeerRecord) {
        if (!record.verifySignature()) return
        valueStore[record.peerId] = record

        // Replicate to closest nodes
        val closest = routingTable.getClosestNodes(record.peerId, 10)
        val envelope = DhtEnvelope(
            rpcId = UUID.randomUUID().toString(),
            type = DhtRpcType.PUT_VALUE,
            senderPeerId = localPeerId,
            peerRecord = record
        )
        for (contact in closest) {
            for (addrStr in contact.multiaddresses) {
                try {
                    val ma = Multiaddress.fromString(addrStr)
                    sendRpc(envelope, InetAddress.getByName(ma.host), ma.port)
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun queryPeer(targetPeerId: String): PeerRecord? = withContext(Dispatchers.IO) {
        // 1. Check local cache
        val cached = valueStore[targetPeerId]
        if (cached != null && !cached.isExpired()) return@withContext cached

        // 2. Query closest nodes in routing table
        val closest = routingTable.getClosestNodes(targetPeerId, 6)
        for (contact in closest) {
            for (addrStr in contact.multiaddresses) {
                try {
                    val ma = Multiaddress.fromString(addrStr)
                    val rpcId = UUID.randomUUID().toString()
                    val req = DhtEnvelope(
                        rpcId = rpcId,
                        type = DhtRpcType.GET_VALUE,
                        senderPeerId = localPeerId,
                        targetPeerId = targetPeerId
                    )
                    sendRpc(req, InetAddress.getByName(ma.host), ma.port)
                } catch (_: Exception) {}
            }
        }
        delay(500)
        valueStore[targetPeerId]?.takeIf { !it.isExpired() }
    }

    fun pingNode(multiaddress: Multiaddress) {
        val req = DhtEnvelope(
            rpcId = UUID.randomUUID().toString(),
            type = DhtRpcType.PING,
            senderPeerId = localPeerId
        )
        try {
            sendRpc(req, InetAddress.getByName(multiaddress.host), multiaddress.port)
        } catch (e: Exception) {
            Log.d(TAG, "Ping node failed: ${e.message}")
        }
    }

    private fun sendRpc(envelope: DhtEnvelope, addr: InetAddress, port: Int) {
        try {
            val bytes = envelope.toJson().toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, addr, port)
            dhtSocket?.send(packet)
        } catch (_: Exception) {}
    }

    private fun republishPresence() {
        val localRecord = valueStore[localPeerId] ?: return
        publishRecord(localRecord)
    }

    fun stop() {
        running = false
        workerJob?.cancel()
        try {
            dhtSocket?.close()
        } catch (_: Exception) {}
    }
}
