package com.example.p2p

import android.content.Context
import android.util.Log
import com.example.crypto.IdentityManager
import com.example.data.AppDatabase
import com.example.data.PeerEntity
import com.example.messaging.ChatManager
import com.example.transfer.FileTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

data class NodeStatus(
    val isRunning: Boolean = false,
    val peerId: String = "",
    val displayName: String = "",
    val listeningPort: Int = 9091,
    val localAddresses: List<String> = emptyList(),
    val observedPublicAddress: String? = null,
    val dhtRoutingTableSize: Int = 0,
    val activeConnectionsCount: Int = 0
)

/**
 * Top-level decentralized P2P Node.
 * Integrates crypto identity, Kademlia DHT, socket transport,
 * mutual authentication, Double Ratchet sessions, and direct file transfers.
 */
class P2PNode(
    val context: Context,
    val database: AppDatabase = AppDatabase.getInstance(context),
    val identityManager: IdentityManager = IdentityManager(context)
) {
    companion object {
        private const val TAG = "P2PNode"

        @Volatile
        private var INSTANCE: P2PNode? = null

        fun getInstance(context: Context): P2PNode {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: P2PNode(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dhtManager = DhtManager(identityManager.peerId, identityManager)

    lateinit var connectionManager: ConnectionManager
        private set

    lateinit var transportManager: TransportManager
        private set

    lateinit var peerDiscovery: PeerDiscovery
        private set

    lateinit var chatManager: ChatManager
        private set

    lateinit var fileTransferManager: FileTransferManager
        private set

    val circuitHopperManager = CircuitHopperManager()

    private val _nodeStatus = MutableStateFlow(
        NodeStatus(
            peerId = identityManager.peerId,
            displayName = identityManager.displayName
        )
    )
    val nodeStatus: StateFlow<NodeStatus> = _nodeStatus.asStateFlow()

    init {
        initSubsystems()
    }

    private fun initSubsystems() {
        connectionManager = ConnectionManager(
            scope = nodeScope,
            identityManager = identityManager,
            onMessageReceived = { env, payload ->
                chatManager.handleIncomingMessage(env, payload)
            },
            onFileEnvelopeReceived = { env ->
                fileTransferManager.handleIncomingFileEnvelope(env)
            },
            onPeerStateChanged = { peerId, state, reason ->
                nodeScope.launch(Dispatchers.IO) {
                    database.peerDao().updateConnectionState(peerId, state.name)
                    if (state == PeerConnectionState.CONNECTED) {
                        database.peerDao().updateOnlineStatus(peerId, true, System.currentTimeMillis())
                    }
                    updateStatus()
                }
            },
            onPeerRecordDiscovered = { record ->
                handleDiscoveredPeer(record)
            }
        )

        chatManager = ChatManager(nodeScope, database, connectionManager)
        fileTransferManager = FileTransferManager(context, nodeScope, database, connectionManager)

        transportManager = TransportManager(
            requestedPort = 9091,
            onInboundConnection = { socket ->
                connectionManager.handleInboundSocket(socket)
            }
        )

        peerDiscovery = PeerDiscovery(
            context = context,
            identityManager = identityManager,
            onPeerDiscovered = { record ->
                handleDiscoveredPeer(record)
            }
        )
    }

    fun start() {
        if (_nodeStatus.value.isRunning) return

        transportManager.start(nodeScope)
        val multiaddrs = transportManager.getLocalMultiaddresses()
        peerDiscovery.updateLocalAddresses(multiaddrs)
        peerDiscovery.start(nodeScope)
        dhtManager.start(nodeScope)
        circuitHopperManager.start(nodeScope)

        // Publish self presence to DHT
        val selfRecord = PeerRecord.createSigned(identityManager, multiaddrs)
        dhtManager.publishRecord(selfRecord)

        _nodeStatus.value = _nodeStatus.value.copy(
            isRunning = true,
            listeningPort = transportManager.listeningPort,
            localAddresses = multiaddrs
        )

        // Bootstrap known community DHT nodes
        for (bootstrapStr in DhtManager.DEFAULT_BOOTSTRAP_NODES) {
            try {
                dhtManager.pingNode(Multiaddress.fromString(bootstrapStr))
            } catch (_: Exception) {}
        }
    }

    private fun handleDiscoveredPeer(record: PeerRecord) {
        nodeScope.launch(Dispatchers.IO) {
            dhtManager.routingTable.update(
                DhtRoutingTable.Contact(
                    peerId = record.peerId,
                    multiaddresses = record.addresses
                )
            )

            val addrsJson = JSONArray().apply { record.addresses.forEach { put(it) } }.toString()
            val existing = database.peerDao().getPeer(record.peerId)
            val isConnected = connectionManager.isConnected(record.peerId)
            val connState = if (isConnected) "CONNECTED" else (existing?.connectionState ?: "DISCONNECTED")

            val peer = PeerEntity(
                peerId = record.peerId,
                displayName = record.displayName,
                publicKeyHex = record.publicKeyHex,
                dhPublicKeyHex = record.dhPublicKeyHex,
                addressesJson = addrsJson,
                lastSeen = record.timestamp,
                isOnline = true,
                isDirectReachable = true,
                isVerified = existing?.isVerified ?: false,
                connectionState = connState
            )
            database.peerDao().upsertPeer(peer)
            updateStatus()
        }
    }

    fun connectToPeer(peerId: String) {
        nodeScope.launch(Dispatchers.IO) {
            val peer = database.peerDao().getPeer(peerId) ?: return@launch
            val addrs = mutableListOf<String>()
            try {
                val arr = JSONArray(peer.addressesJson)
                for (i in 0 until arr.length()) addrs.add(arr.getString(i))
            } catch (_: Exception) {}

            val record = PeerRecord(
                peerId = peer.peerId,
                displayName = peer.displayName,
                publicKeyHex = peer.publicKeyHex,
                dhPublicKeyHex = peer.dhPublicKeyHex,
                addresses = addrs,
                timestamp = peer.lastSeen,
                signatureHex = "" // Outbound direct connection by verified record
            )
            connectionManager.connectToPeer(record)
        }
    }

    fun lookupAndConnect(peerId: String) {
        nodeScope.launch(Dispatchers.IO) {
            val record = dhtManager.queryPeer(peerId)
            if (record != null) {
                handleDiscoveredPeer(record)
                connectionManager.connectToPeer(record)
            } else {
                // If not found in DHT, check if we have stored record in Room
                val localPeer = database.peerDao().getPeer(peerId)
                if (localPeer != null) {
                    connectToPeer(peerId)
                }
            }
        }
    }

    fun broadcastDiscoveryNow() {
        nodeScope.launch(Dispatchers.IO) {
            val multiaddrs = transportManager.getLocalMultiaddresses()
            peerDiscovery.updateLocalAddresses(multiaddrs)
            _nodeStatus.value = _nodeStatus.value.copy(localAddresses = multiaddrs)
            peerDiscovery.broadcastNow()
        }
    }

    fun connectDirectlyToAddress(targetInput: String) {
        nodeScope.launch(Dispatchers.IO) {
            try {
                val ma = Multiaddress.fromString(targetInput)
                val formattedAddr = "/ip4/${ma.host}/tcp/${ma.port}"
                val tempPeerId = "peer_${ma.host.replace(".", "_")}_${ma.port}"
                val tempRecord = PeerRecord(
                    peerId = tempPeerId,
                    displayName = "Device@${ma.host}",
                    publicKeyHex = "",
                    dhPublicKeyHex = "",
                    addresses = listOf(formattedAddr),
                    timestamp = System.currentTimeMillis(),
                    signatureHex = ""
                )
                val peer = PeerEntity(
                    peerId = tempPeerId,
                    displayName = tempRecord.displayName,
                    publicKeyHex = "",
                    dhPublicKeyHex = "",
                    addressesJson = JSONArray().apply { put(formattedAddr) }.toString(),
                    lastSeen = System.currentTimeMillis(),
                    isOnline = true,
                    isDirectReachable = true,
                    isVerified = false,
                    connectionState = "CONNECTING"
                )
                database.peerDao().upsertPeer(peer)
                connectionManager.connectToPeer(tempRecord)
            } catch (e: Exception) {
                Log.e(TAG, "Direct connect error: ${e.message}", e)
            }
        }
    }

    fun sendMessage(recipientPeerId: String, text: String) {
        chatManager.sendMessage(recipientPeerId, text)
    }

    fun sendFile(recipientPeerId: String, file: File) {
        fileTransferManager.sendFile(recipientPeerId, file)
    }

    private fun updateStatus() {
        _nodeStatus.value = _nodeStatus.value.copy(
            dhtRoutingTableSize = dhtManager.routingTable.size(),
            activeConnectionsCount = connectionManager.getActiveConnectionsCount(),
            observedPublicAddress = dhtManager.observedPublicAddress
        )
    }

    fun updatePeerVerification(peerId: String, isVerified: Boolean) {
        nodeScope.launch(Dispatchers.IO) {
            database.peerDao().updateVerified(peerId, isVerified)
        }
    }

    fun stop() {
        circuitHopperManager.stop()
        peerDiscovery.stop()
        dhtManager.stop()
        connectionManager.disconnectAll()
        transportManager.stop()
        _nodeStatus.value = _nodeStatus.value.copy(isRunning = false)
    }
}
