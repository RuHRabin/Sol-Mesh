package com.example.p2p.libp2p

import android.content.Context
import android.util.Log
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.crypto.keys.unmarshalEd25519PrivateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Node identification details for the decentralized libp2p host.
 */
data class Libp2pNodeInfo(
    val peerIdBase58: String,
    val peerIdHex: String,
    val listenAddresses: List<String>,
    val keyType: String = "ED25519",
    val isRunning: Boolean = false,
    val activeConnectionsCount: Int = 0
)

/**
 * Metadata for an active connection managed by the libp2p host.
 */
data class Libp2pConnectionInfo(
    val connectionId: String,
    val remotePeerIdBase58: String,
    val remoteAddress: String,
    val localAddress: String,
    val isInitiator: Boolean,
    val connectedAtMillis: Long = System.currentTimeMillis()
)

/**
 * High-level lifecycle states of the libp2p host.
 */
sealed class HostLifecycleState {
    object Stopped : HostLifecycleState()
    object Starting : HostLifecycleState()
    data class Running(val nodeInfo: Libp2pNodeInfo) : HostLifecycleState()
    object Stopping : HostLifecycleState()
    data class Error(val cause: Throwable, val message: String) : HostLifecycleState()
}

/**
 * Asynchronous lifecycle and network events emitted by the libp2p host.
 */
sealed class Libp2pEvent {
    data class HostStarted(val nodeInfo: Libp2pNodeInfo) : Libp2pEvent()
    object HostStopped : Libp2pEvent()
    data class HostError(val error: Throwable, val message: String) : Libp2pEvent()
    data class PeerConnected(val connection: Libp2pConnectionInfo) : Libp2pEvent()
    data class PeerDisconnected(val peerIdBase58: String, val connectionId: String, val reason: String? = null) : Libp2pEvent()
    data class DialFailed(val targetAddress: String, val error: Throwable) : Libp2pEvent()
}

/**
 * Libp2p Host Service managing:
 * 1. Node identification (Ed25519 cryptographic keypair and Base58/Hex PeerID).
 * 2. Connection listening (Standard TCP transport, Noise secure channel, Mplex/Yamux stream muxing).
 * 3. Lifecycle events (starting, running, stopping, and peer connection state transitions).
 */
class Libp2pHostService(
    private val context: Context? = null,
    private val keyPairProvider: (() -> Pair<PrivKey, PubKey>)? = null,
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "Libp2pHostService"
        const val DEFAULT_PORT = 4001
        private const val PREFS_NAME = "sol_mesh_libp2p_prefs"
        private const val KEY_PRIV_KEY_HEX = "libp2p_ed25519_priv_hex"

        @Volatile
        private var INSTANCE: Libp2pHostService? = null

        fun getInstance(context: Context): Libp2pHostService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Libp2pHostService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var host: Host? = null
    private var privKey: PrivKey? = null
    private var pubKey: PubKey? = null

    // Lifecycle state management
    private val _lifecycleState = MutableStateFlow<HostLifecycleState>(HostLifecycleState.Stopped)
    val lifecycleState: StateFlow<HostLifecycleState> = _lifecycleState.asStateFlow()

    // Node identification metadata
    private val _nodeInfo = MutableStateFlow<Libp2pNodeInfo?>(null)
    val nodeInfo: StateFlow<Libp2pNodeInfo?> = _nodeInfo.asStateFlow()

    // Active connections tracker: connectionId -> Libp2pConnectionInfo
    private val activeConnectionsMap = ConcurrentHashMap<String, Libp2pConnectionInfo>()
    private val _activeConnections = MutableStateFlow<List<Libp2pConnectionInfo>>(emptyList())
    val activeConnections: StateFlow<List<Libp2pConnectionInfo>> = _activeConnections.asStateFlow()

    // Real-time network and lifecycle event bus
    private val _events = MutableSharedFlow<Libp2pEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<Libp2pEvent> = _events.asSharedFlow()

    /**
     * Retrieves or generates persistent Ed25519 node identity keypair.
     */
    fun getOrCreateIdentity(): Pair<PrivKey, PubKey> {
        if (privKey != null && pubKey != null) {
            return Pair(privKey!!, pubKey!!)
        }

        keyPairProvider?.let {
            val pair = it()
            privKey = pair.first
            pubKey = pair.second
            return pair
        }

        // Check persistent Android SharedPreferences if context is available
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPrivHex = prefs?.getString(KEY_PRIV_KEY_HEX, null)

        if (!savedPrivHex.isNullOrEmpty()) {
            try {
                val privBytes = hexToBytes(savedPrivHex)
                val restoredPriv = unmarshalEd25519PrivateKey(privBytes)
                val restoredPub = restoredPriv.publicKey()
                privKey = restoredPriv
                pubKey = restoredPub
                Log.d(TAG, "Restored existing libp2p node identity: ${PeerId.fromPubKey(restoredPub).toBase58()}")
                return Pair(restoredPriv, restoredPub)
            } catch (e: Exception) {
                Log.w(TAG, "Failed restoring saved private key, regenerating: ${e.message}")
            }
        }

        // Generate fresh Ed25519 identity keypair
        val newPair = generateEd25519KeyPair()
        privKey = newPair.first
        pubKey = newPair.second

        prefs?.edit()?.putString(KEY_PRIV_KEY_HEX, bytesToHex(newPair.first.raw()))?.apply()
        Log.i(TAG, "Generated new libp2p node identity: ${PeerId.fromPubKey(newPair.second).toBase58()}")
        return newPair
    }

    /**
     * Starts the libp2p Host, binds listening sockets, and initiates connection handling.
     */
    suspend fun start(
        listenPort: Int = DEFAULT_PORT,
        bindHost: String = "0.0.0.0"
    ): Result<Libp2pNodeInfo> = withContext(Dispatchers.IO) {
        if (_lifecycleState.value is HostLifecycleState.Running) {
            Log.d(TAG, "Libp2p Host is already running.")
            return@withContext Result.success(_nodeInfo.value!!)
        }

        _lifecycleState.value = HostLifecycleState.Starting

        try {
            val (privateKey, publicKey) = getOrCreateIdentity()
            val peerId = PeerId.fromPubKey(publicKey)
            val listenMultiaddrStr = "/ip4/$bindHost/tcp/$listenPort"

            Log.i(TAG, "Initializing libp2p Host for PeerID: ${peerId.toBase58()} on $listenMultiaddrStr")

            // Build Host using standard defaults (TCP, Noise, Yamux/Mplex)
            val hostInstance = HostBuilder(HostBuilder.DefaultMode.Standard)
                .listen(listenMultiaddrStr)
                .keyType(KeyType.ED25519)
                .builderModifier { builder ->
                    builder.identity.factory = { privateKey }
                }
                .build()

            // Attach ConnectionHandler for lifecycle and peer tracking
            hostInstance.addConnectionHandler(createConnectionHandler())

            // Start listening
            hostInstance.start().await()
            host = hostInstance

            val formattedListenAddrs = hostInstance.listenAddresses().map { it.toString() }
            val currentInfo = Libp2pNodeInfo(
                peerIdBase58 = peerId.toBase58(),
                peerIdHex = peerId.toHex(),
                listenAddresses = if (formattedListenAddrs.isNotEmpty()) formattedListenAddrs else listOf(listenMultiaddrStr),
                keyType = "ED25519",
                isRunning = true,
                activeConnectionsCount = 0
            )

            _nodeInfo.value = currentInfo
            _lifecycleState.value = HostLifecycleState.Running(currentInfo)
            _events.emit(Libp2pEvent.HostStarted(currentInfo))

            Log.i(TAG, "Libp2p Host successfully started. Listening on: $formattedListenAddrs")
            Result.success(currentInfo)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed starting libp2p Host: ${t.message}", t)
            _lifecycleState.value = HostLifecycleState.Error(t, t.message ?: "Failed starting Host")
            _events.emit(Libp2pEvent.HostError(t, t.message ?: "Failed starting Host"))
            Result.failure(t)
        }
    }

    /**
     * Dials a remote libp2p node by its multiaddress (e.g. /ip4/192.168.1.5/tcp/4001).
     */
    suspend fun dial(targetMultiaddress: String): Result<Libp2pConnectionInfo> = withContext(Dispatchers.IO) {
        val currentHost = host
        if (currentHost == null || _lifecycleState.value !is HostLifecycleState.Running) {
            val err = IllegalStateException("Cannot dial peer: Libp2p host is not running")
            _events.emit(Libp2pEvent.DialFailed(targetMultiaddress, err))
            return@withContext Result.failure(err)
        }

        try {
            val targetAddr = Multiaddr.fromString(targetMultiaddress)
            Log.d(TAG, "Dialing remote libp2p multiaddress: $targetMultiaddress")

            val connectionFuture = currentHost.network.connect(targetAddr)
            val connection = connectionFuture.await()

            val remoteId = try {
                connection.secureSession().remoteId.toBase58()
            } catch (_: Exception) {
                targetAddr.getPeerId()?.toBase58() ?: "unknown"
            }

            val connInfo = Libp2pConnectionInfo(
                connectionId = "${remoteId}_${System.currentTimeMillis()}",
                remotePeerIdBase58 = remoteId,
                remoteAddress = connection.remoteAddress().toString(),
                localAddress = connection.localAddress().toString(),
                isInitiator = true
            )

            recordConnection(connInfo, connection)
            Result.success(connInfo)
        } catch (t: Throwable) {
            Log.e(TAG, "Dial failed to $targetMultiaddress: ${t.message}", t)
            _events.emit(Libp2pEvent.DialFailed(targetMultiaddress, t))
            Result.failure(t)
        }
    }

    /**
     * Closes an active connection by its remote peer ID.
     */
    suspend fun disconnectPeer(peerIdBase58: String): Boolean = withContext(Dispatchers.IO) {
        val currentHost = host ?: return@withContext false
        val activeConn = activeConnectionsMap.values.firstOrNull { it.remotePeerIdBase58 == peerIdBase58 } ?: return@withContext false

        return@withContext try {
            val connections = currentHost.network.connections
            val matchedConn = connections.firstOrNull { conn ->
                try {
                    conn.secureSession().remoteId.toBase58() == peerIdBase58
                } catch (_: Exception) {
                    false
                }
            }

            if (matchedConn != null) {
                currentHost.network.disconnect(matchedConn).await()
            }
            removeConnection(activeConn.connectionId, "Disconnected by user")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting peer $peerIdBase58: ${e.message}")
            false
        }
    }

    /**
     * Gracefully stops the libp2p Host and closes all active listening and transport channels.
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        val currentHost = host
        if (currentHost == null) {
            _lifecycleState.value = HostLifecycleState.Stopped
            return@withContext Result.success(Unit)
        }

        _lifecycleState.value = HostLifecycleState.Stopping
        try {
            Log.i(TAG, "Stopping libp2p Host...")
            currentHost.stop().await()
            host = null

            activeConnectionsMap.clear()
            _activeConnections.value = emptyList()

            _nodeInfo.value = _nodeInfo.value?.copy(isRunning = false, activeConnectionsCount = 0)
            _lifecycleState.value = HostLifecycleState.Stopped
            _events.emit(Libp2pEvent.HostStopped)

            Log.i(TAG, "Libp2p Host stopped successfully.")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Error while stopping libp2p Host: ${t.message}", t)
            _lifecycleState.value = HostLifecycleState.Error(t, t.message ?: "Failed stopping host")
            Result.failure(t)
        }
    }

    /**
     * Restarts the libp2p host on the specified listening port.
     */
    suspend fun restart(port: Int = DEFAULT_PORT): Result<Libp2pNodeInfo> {
        stop()
        return start(port)
    }

    private fun createConnectionHandler(): ConnectionHandler {
        return ConnectionHandler { conn: Connection ->
            serviceScope.launch {
                handleInboundConnection(conn)
            }
        }
    }

    private suspend fun handleInboundConnection(conn: Connection) {
        val remotePeerId = try {
            conn.secureSession().remoteId.toBase58()
        } catch (_: Exception) {
            "unknown_peer"
        }

        val connInfo = Libp2pConnectionInfo(
            connectionId = "${remotePeerId}_${System.currentTimeMillis()}",
            remotePeerIdBase58 = remotePeerId,
            remoteAddress = conn.remoteAddress().toString(),
            localAddress = conn.localAddress().toString(),
            isInitiator = conn.isInitiator
        )

        recordConnection(connInfo, conn)
    }

    private suspend fun recordConnection(connInfo: Libp2pConnectionInfo, conn: Connection) {
        activeConnectionsMap[connInfo.connectionId] = connInfo
        updateActiveConnectionsList()

        _events.emit(Libp2pEvent.PeerConnected(connInfo))
        Log.i(TAG, "Peer connected: ${connInfo.remotePeerIdBase58} [${connInfo.remoteAddress}]")

        // Hook connection closure future
        conn.closeFuture().whenComplete { _, throwable ->
            serviceScope.launch {
                removeConnection(
                    connectionId = connInfo.connectionId,
                    reason = throwable?.message ?: "Connection closed"
                )
            }
        }
    }

    private suspend fun removeConnection(connectionId: String, reason: String?) {
        val removed = activeConnectionsMap.remove(connectionId)
        if (removed != null) {
            updateActiveConnectionsList()
            _events.emit(Libp2pEvent.PeerDisconnected(removed.remotePeerIdBase58, connectionId, reason))
            Log.i(TAG, "Peer disconnected: ${removed.remotePeerIdBase58} reason: $reason")
        }
    }

    private fun updateActiveConnectionsList() {
        val list = activeConnectionsMap.values.toList()
        _activeConnections.value = list
        _nodeInfo.value = _nodeInfo.value?.copy(activeConnectionsCount = list.size)
    }

    fun shutdown() {
        serviceScope.launch {
            stop()
            serviceScope.cancel()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i shr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
