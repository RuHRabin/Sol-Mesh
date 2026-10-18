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
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Who may talk to us: accepted people, pending requests and blocked people. */
    val contactPolicy = ContactPolicy(SharedPrefsContactStore(context))

    /** Called with (peerId, displayName) when an unknown person asks to connect. */
    @Volatile
    var onContactRequest: ((String, String) -> Unit)? = null

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
                        // deliver anything written while this peer was offline
                        chatManager.retryUnsent(peerId)
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

        connectionManager.onInboundAuthenticated = { record, autoAccept ->
            when (contactPolicy.onInbound(record.peerId, autoAccept)) {
                ContactPolicy.Decision.REJECT -> false
                ContactPolicy.Decision.PENDING_NEW -> {
                    onContactRequest?.invoke(record.peerId, record.displayName)
                    true
                }
                else -> true
            }
        }
        connectionManager.onOutboundAuthenticated = { peerId -> contactPolicy.onOutbound(peerId) }
        fileTransferManager.isPeerAllowed = { peerId -> contactPolicy.isAccepted(peerId) }

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

    private val startRequested = AtomicBoolean(false)

    /**
     * Starts all subsystems. Safe to call repeatedly and from the main thread:
     * the actual socket work runs on the IO dispatcher.
     */
    fun start() {
        if (!startRequested.compareAndSet(false, true)) return
        nodeScope.launch(Dispatchers.IO) { startSubsystems() }
    }

    private fun startSubsystems() {
        // One-time upgrade: people already in the list before requests existed stay accepted
        try {
            kotlinx.coroutines.runBlocking {
                contactPolicy.migrateExisting(database.peerDao().getAllPeers().map { it.peerId })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact migration skipped: ${e.message}")
        }
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
                val formattedAddr = ma.toString() // picks /ip4/, /ip6/ correctly
                val tempPeerId = "peer_${ma.host.replace(".", "_").replace(":", "_")}_${ma.port}"
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

    // ---- Connecting across different networks (NAT hole punching, no relay server) ----

    private val _publicAddress = MutableStateFlow<String?>(null)

    /** Our address code (IPv6 and/or public IPv4) to copy and send to the other phone. */
    val publicAddress: StateFlow<String?> = _publicAddress.asStateFlow()

    private val _natStatus = MutableStateFlow("")

    /** Human readable progress / result of the last internet-connect action. */
    val natStatus: StateFlow<String> = _natStatus.asStateFlow()

    private val punchRunning = AtomicBoolean(false)
    private var myCandidates: NatTraversal.Candidates? = null

    private fun rememberCandidates(c: NatTraversal.Candidates) {
        myCandidates = c
        _publicAddress.value = c.toShareCode()
    }

    /** Finds our IPv6 address and public IPv4 address so the user can copy them to the other phone. */
    fun discoverPublicAddress() {
        if (punchRunning.get()) return
        nodeScope.launch(Dispatchers.IO) {
            _natStatus.value = "Looking up your internet address..."
            val c = NatTraversal.discoverCandidates()
            if (!c.isEmpty()) {
                rememberCandidates(c)
                _natStatus.value = "Your address code is ready. Copy it and send it to the other person." +
                    if (c.v6 == null) " (This network has no IPv6.)" else ""
            } else {
                _publicAddress.value = null
                _natStatus.value = "Could not find an internet address. Check your connection and try again."
            }
        }
    }

    /**
     * Tries to open a direct connection to the other phone using its address code.
     * Both phones must press this at about the same time (within ~20 seconds).
     */
    fun punchConnect(remoteInput: String) {
        if (!punchRunning.compareAndSet(false, true)) return
        nodeScope.launch(Dispatchers.IO) {
            try {
                val theirs = NatTraversal.parseShareCode(remoteInput)
                if (theirs.isEmpty()) {
                    _natStatus.value = "Invalid address code. Paste exactly what the other phone copied."
                    return@launch
                }

                val mine = myCandidates ?: NatTraversal.discoverCandidates().also {
                    if (!it.isEmpty()) rememberCandidates(it)
                }
                if (mine.isEmpty()) {
                    _natStatus.value = "Could not find your own internet address. Check your connection."
                    return@launch
                }

                val pair = NatTraversal.choosePair(mine, theirs)
                if (pair == null) {
                    _natStatus.value = "These two phones have no address type in common " +
                        "(one has only IPv6, the other only IPv4)."
                    return@launch
                }
                val (myAddr, theirAddr) = pair
                val (host, port) = NatTraversal.parseHostPort(theirAddr)!!
                val myHost = NatTraversal.parseHostPort(myAddr)!!.first
                val isV6 = host.contains(':')

                _natStatus.value = "Connecting over ${if (isV6) "IPv6" else "IPv4"}... " +
                    "keep the app open on both phones (up to 30 seconds)."
                val socket = NatTraversal.punch(host, port, localAddress = if (isV6) myHost else null)
                if (socket == null) {
                    _natStatus.value = "No direct path found. One of the two networks blocks it " +
                        "(common on mobile data without IPv6). Try Wi-Fi on both phones, or use a relay server."
                    return@launch
                }

                // Both phones share ONE TCP connection now. The phone with the smaller address string
                // acts as handshake initiator, the other as responder (both sides compute the same order).
                if (myAddr < theirAddr) {
                    val family = if (isV6) "ip6" else "ip4"
                    val tempPeerId = "peer_" + host.replace(".", "_").replace(":", "_") + "_" + port
                    val tempRecord = PeerRecord(
                        peerId = tempPeerId,
                        displayName = "Device@$host",
                        publicKeyHex = "",
                        dhPublicKeyHex = "",
                        addresses = listOf("/$family/$host/tcp/$port"),
                        timestamp = System.currentTimeMillis(),
                        signatureHex = ""
                    )
                    connectionManager.adoptOutboundSocket(tempRecord, socket)
                } else {
                    // both people started this connection on purpose, so no request is needed
                    connectionManager.handleInboundSocket(socket, autoAccept = true)
                }
                _natStatus.value = "Connected. Open the Chats tab to chat or send files."
            } catch (e: Exception) {
                Log.e(TAG, "Punch connect error: ${e.message}", e)
                _natStatus.value = "Connection error: ${e.message}"
            } finally {
                punchRunning.set(false)
            }
        }
    }

    /**
     * Connects to the other phone through a relay server (works on any network, including mobile data).
     * Both phones must enter the same relay address and the same one-time code.
     * The relay only forwards bytes; messages stay end-to-end encrypted.
     */
    fun relayConnect(relayInput: String, codeInput: String) {
        if (!punchRunning.compareAndSet(false, true)) return
        nodeScope.launch(Dispatchers.IO) {
            try {
                val code = codeInput.trim()
                if (!RelayClient.isValidCode(code)) {
                    _natStatus.value = "The code must be 8 to 64 letters or digits."
                    return@launch
                }
                val relay = RelayClient.parseRelayAddress(relayInput)
                if (relay == null) {
                    _natStatus.value = "Invalid relay address. Use something like relay.example.com:9000"
                    return@launch
                }
                _natStatus.value = "Connecting to the relay... waiting for the other phone to join with the same code (up to 2 minutes)."
                val paired = RelayClient.join(relay.first, relay.second, code)
                if (paired == null) {
                    _natStatus.value = "The other phone did not join in time. Start again on both phones."
                    return@launch
                }
                if (paired.initiator) {
                    val tempRecord = PeerRecord(
                        peerId = "peer_relay_" + code.take(8),
                        displayName = "Device via relay",
                        publicKeyHex = "",
                        dhPublicKeyHex = "",
                        addresses = emptyList(),
                        timestamp = System.currentTimeMillis(),
                        signatureHex = ""
                    )
                    connectionManager.adoptOutboundSocket(tempRecord, paired.socket)
                } else {
                    // both people entered the same code, so no request is needed
                    connectionManager.handleInboundSocket(paired.socket, autoAccept = true)
                }
                _natStatus.value = "Connected through the relay. Open the Chats tab to chat or send files."
            } catch (e: Exception) {
                Log.e(TAG, "Relay connect error: ${e.message}", e)
                _natStatus.value = "Relay error: ${e.message}"
            } finally {
                punchRunning.set(false)
            }
        }
    }

    fun sendMessage(recipientPeerId: String, text: String) {
        chatManager.sendMessage(recipientPeerId, text)
    }

    fun retryMessage(messageId: String) {
        chatManager.retryMessage(messageId)
    }

    // ---- Connection requests ----

    fun acceptContact(peerId: String) {
        contactPolicy.accept(peerId)
    }

    /** "Not now": disconnects and forgets them. They are asked about again if they reconnect. */
    fun declineContact(peerId: String) {
        contactPolicy.decline(peerId)
        removeContact(peerId)
    }

    fun blockContact(peerId: String) {
        contactPolicy.block(peerId)
        removeContact(peerId)
    }

    fun unblockContact(peerId: String) {
        contactPolicy.unblock(peerId)
    }

    private fun removeContact(peerId: String) {
        connectionManager.disconnect(peerId)
        nodeScope.launch(Dispatchers.IO) {
            database.messageDao().deleteConversation(peerId)
            database.peerDao().deletePeer(peerId)
        }
    }

    fun sendFile(recipientPeerId: String, file: File, mimeType: String = "application/octet-stream") {
        fileTransferManager.sendFile(recipientPeerId, file, mimeType)
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
        startRequested.set(false)
        circuitHopperManager.stop()
        peerDiscovery.stop()
        dhtManager.stop()
        connectionManager.disconnectAll()
        transportManager.stop()
        _nodeStatus.value = _nodeStatus.value.copy(isRunning = false)
    }
}
