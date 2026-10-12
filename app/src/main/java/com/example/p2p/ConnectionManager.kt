package com.example.p2p

import android.util.Log
import com.example.crypto.Curve25519
import com.example.crypto.DoubleRatchetSession
import com.example.crypto.Hkdf
import com.example.crypto.IdentityManager
import com.example.protocol.ChatEnvelope
import com.example.protocol.ChatPlaintextPayload
import com.example.protocol.FileEnvelope
import com.example.protocol.HandshakeEnvelope
import com.example.protocol.HandshakeStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P2P Connection Manager.
 * Handles direct TCP socket connections, 3-way mutual authentication,
 * Double Ratchet E2EE session setup, multiplexing and frame dispatching.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val identityManager: IdentityManager,
    private val onMessageReceived: (ChatEnvelope, ChatPlaintextPayload) -> Unit,
    private val onFileEnvelopeReceived: (FileEnvelope) -> Unit,
    private val onPeerStateChanged: (String, PeerConnectionState, String?) -> Unit,
    private val onPeerRecordDiscovered: (PeerRecord) -> Unit = {}
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HANDSHAKE_MAX_SKEW_MS = 10 * 60 * 1000L
        private const val MAX_DISPLAY_NAME_LENGTH = 64
    }

    data class ActiveConnection(
        val peerId: String,
        var socket: Socket,
        var stream: FramedSocketStream,
        var session: DoubleRatchetSession,
        var displayName: String,
        var readerJob: Job? = null
    )

    private val connections = ConcurrentHashMap<String, ActiveConnection>()
    private val peerStates = ConcurrentHashMap<String, MutableStateFlow<PeerConnectionState>>()

    fun getPeerStateFlow(peerId: String): StateFlow<PeerConnectionState> {
        return peerStates.getOrPut(peerId) {
            MutableStateFlow(PeerConnectionState.DISCONNECTED)
        }.asStateFlow()
    }

    private fun setPeerState(peerId: String, state: PeerConnectionState, reason: String? = null) {
        val flow = peerStates.getOrPut(peerId) { MutableStateFlow(state) }
        flow.value = state
        onPeerStateChanged(peerId, state, reason)
    }

    fun isConnected(peerId: String): Boolean {
        return connections[peerId]?.stream?.isConnected == true
    }

    fun getActiveConnectionsCount(): Int = connections.values.count { it.stream.isConnected }

    /**
     * Connects directly to a remote peer using their advertised multiaddresses or direct IP.
     */
    fun connectToPeer(record: PeerRecord) {
        if (isConnected(record.peerId)) return

        scope.launch(Dispatchers.IO) {
            setPeerState(record.peerId, PeerConnectionState.CONNECTING)

            var connectedSocket: Socket? = null
            var lastError: String? = null

            // Try direct connection to each multiaddress
            for (addrStr in record.addresses) {
                try {
                    val ma = Multiaddress.fromString(addrStr)
                    Log.d(TAG, "Attempting direct socket to $ma for peer ${record.peerId}")
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(ma.host, ma.port), CONNECT_TIMEOUT_MS)
                    connectedSocket = sock
                    break
                } catch (e: Exception) {
                    lastError = e.message
                    Log.d(TAG, "Connection failed to $addrStr: ${e.message}")
                }
            }

            if (connectedSocket == null) {
                val failureReason = "Connection failed: ${lastError ?: "Peer unreachable on Wi-Fi"}"
                setPeerState(record.peerId, PeerConnectionState.FAILED, failureReason)
                return@launch
            }

            // Establish authenticated session as initiator
            try {
                setPeerState(record.peerId, PeerConnectionState.AUTHENTICATING)
                val stream = FramedSocketStream(connectedSocket)
                val handshakeResult = performInitiatorHandshake(stream, record)
                if (handshakeResult != null) {
                    val (actualRecord, session) = handshakeResult
                    val activeConn = ActiveConnection(
                        peerId = actualRecord.peerId,
                        socket = connectedSocket,
                        stream = stream,
                        session = session,
                        displayName = actualRecord.displayName
                    )
                    connections[actualRecord.peerId] = activeConn

                    // If record had placeholder ID, alias it
                    if (record.peerId != actualRecord.peerId) {
                        connections[record.peerId] = activeConn
                        setPeerState(record.peerId, PeerConnectionState.CONNECTED)
                    }

                    setPeerState(actualRecord.peerId, PeerConnectionState.SECURE_SESSION_ESTABLISHED)
                    delay(50)
                    setPeerState(actualRecord.peerId, PeerConnectionState.CONNECTED)

                    // Notify node to save authenticated peer
                    onPeerRecordDiscovered(actualRecord)

                    // Start reader loop
                    val job = scope.launch(Dispatchers.IO) {
                        runReaderLoop(activeConn)
                    }
                    activeConn.readerJob = job
                } else {
                    connectedSocket.close()
                    setPeerState(record.peerId, PeerConnectionState.FAILED, "Handshake verification failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handshake error with ${record.peerId}: ${e.message}", e)
                try { connectedSocket.close() } catch (_: Exception) {}
                setPeerState(record.peerId, PeerConnectionState.FAILED, "Handshake failed: ${e.message}")
            }
        }
    }

    /**
     * Handles inbound peer socket accepted by local TransportManager server.
     */
    fun handleInboundSocket(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            var peerId: String? = null
            try {
                val stream = FramedSocketStream(socket)
                val (peerRecord, session) = performResponderHandshake(stream, socket) ?: run {
                    socket.close()
                    return@launch
                }
                peerId = peerRecord.peerId
                setPeerState(peerId, PeerConnectionState.AUTHENTICATING)

                val activeConn = ActiveConnection(
                    peerId = peerId,
                    socket = socket,
                    stream = stream,
                    session = session,
                    displayName = peerRecord.displayName
                )
                connections[peerId] = activeConn
                setPeerState(peerId, PeerConnectionState.SECURE_SESSION_ESTABLISHED)
                delay(50)
                setPeerState(peerId, PeerConnectionState.CONNECTED)

                // Save discovered remote peer record
                onPeerRecordDiscovered(peerRecord)

                val job = scope.launch(Dispatchers.IO) {
                    runReaderLoop(activeConn)
                }
                activeConn.readerJob = job
            } catch (e: Exception) {
                Log.e(TAG, "Inbound handshake error: ${e.message}", e)
                try { socket.close() } catch (_: Exception) {}
                peerId?.let { setPeerState(it, PeerConnectionState.FAILED, e.message) }
            }
        }
    }

    private fun isHex(s: String, byteLen: Int): Boolean =
        s.length == byteLen * 2 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * Structural and identity checks for a handshake message: well-formed key/signature
     * encodings, PeerID bound to the Ed25519 public key, and a fresh timestamp.
     */
    private fun validateHandshakeIdentity(env: HandshakeEnvelope): Boolean {
        if (!isHex(env.senderEd25519PubHex, 32) ||
            !isHex(env.senderEphemeralDhPubHex, 32) ||
            !isHex(env.signatureHex, 64)) return false
        val staticDh = env.senderStaticDhPubHex
        if (!staticDh.isNullOrEmpty() && !isHex(staticDh, 32)) return false
        if (env.senderDisplayName.length > MAX_DISPLAY_NAME_LENGTH) return false
        val derived = IdentityManager.derivePeerId(IdentityManager.hexToBytes(env.senderEd25519PubHex))
        if (derived != env.senderPeerId) return false
        if (Math.abs(System.currentTimeMillis() - env.timestamp) > HANDSHAKE_MAX_SKEW_MS) return false
        return true
    }

    /** X25519 that rejects the all-zero output produced by low-order public points. */
    private fun safeDh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = Curve25519.x25519ScalarMult(priv, pub)
        if (out.all { it == 0.toByte() }) throw SecurityException("Invalid DH public key")
        return out
    }

    private fun performInitiatorHandshake(
        stream: FramedSocketStream,
        remoteRecord: PeerRecord
    ): Pair<PeerRecord, DoubleRatchetSession>? {
        // Step 1: Send INIT
        val ephemeralPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephemeralPub = Curve25519.x25519PublicFromPrivate(ephemeralPriv)
        val timestamp = System.currentTimeMillis()

        val initMsg = HandshakeEnvelope(
            step = HandshakeStep.INIT,
            senderPeerId = identityManager.peerId,
            senderDisplayName = identityManager.displayName,
            senderEd25519PubHex = identityManager.getPublicKeyHex(),
            senderStaticDhPubHex = identityManager.getDhPublicKeyHex(),
            senderEphemeralDhPubHex = IdentityManager.bytesToHex(ephemeralPub),
            timestamp = timestamp,
            signatureHex = IdentityManager.bytesToHex(
                identityManager.signData(
                    "${HandshakeStep.INIT}|${identityManager.peerId}|${identityManager.displayName}|${identityManager.getPublicKeyHex()}|${identityManager.getDhPublicKeyHex()}|${IdentityManager.bytesToHex(ephemeralPub)}|$timestamp"
                        .toByteArray(Charsets.UTF_8)
                )
            )
        )
        stream.sendFrame(FramedSocketStream.STREAM_HANDSHAKE, initMsg.toJson().toByteArray(Charsets.UTF_8))

        // Step 2: Read RESPONSE
        val respFrame = stream.readFrame()
        if (respFrame.streamId != FramedSocketStream.STREAM_HANDSHAKE) return null
        val resp = HandshakeEnvelope.fromJson(String(respFrame.payload, Charsets.UTF_8)) ?: return null
        if (resp.step != HandshakeStep.RESPONSE) return null
        if (!validateHandshakeIdentity(resp)) return null

        // If remoteRecord had a specific peerId (not an IP placeholder), verify it matches
        if (!remoteRecord.peerId.startsWith("peer_") && remoteRecord.peerId.isNotEmpty()) {
            if (resp.senderPeerId != remoteRecord.peerId) return null
        }

        // Verify responder Ed25519 signature
        val remoteEdPub = IdentityManager.hexToBytes(resp.senderEd25519PubHex)
        val sigValid = Curve25519.ed25519Verify(
            remoteEdPub,
            resp.serializeForSigning(),
            IdentityManager.hexToBytes(resp.signatureHex)
        )
        if (!sigValid) return null

        // Symmetric authenticated Key Agreement:
        val remoteEphemeralPub = IdentityManager.hexToBytes(resp.senderEphemeralDhPubHex)
        val remoteStaticDhPub = if (!resp.senderStaticDhPubHex.isNullOrEmpty()) {
            IdentityManager.hexToBytes(resp.senderStaticDhPubHex)
        } else if (remoteRecord.dhPublicKeyHex.isNotEmpty()) {
            IdentityManager.hexToBytes(remoteRecord.dhPublicKeyHex)
        } else {
            remoteEphemeralPub
        }

        // ee = DH(our_ephemeral, their_ephemeral)
        val ee = safeDh(ephemeralPriv, remoteEphemeralPub)
        // es = DH(our_ephemeral, their_static)
        val es = safeDh(ephemeralPriv, remoteStaticDhPub)
        // se = DH(our_static, their_ephemeral)
        val se = safeDh(identityManager.x25519PrivateKey, remoteEphemeralPub)

        val ikm = ee + es + se
        val rootKey = Hkdf.deriveKey("sol-mesh-v1".toByteArray(), ikm, "NoiseHandshakeRoot".toByteArray(), 32)

        // Step 3: Send CONFIRM
        val confirmMsg = HandshakeEnvelope(
            step = HandshakeStep.CONFIRM,
            senderPeerId = identityManager.peerId,
            senderDisplayName = identityManager.displayName,
            senderEd25519PubHex = identityManager.getPublicKeyHex(),
            senderStaticDhPubHex = identityManager.getDhPublicKeyHex(),
            senderEphemeralDhPubHex = IdentityManager.bytesToHex(ephemeralPub),
            timestamp = System.currentTimeMillis(),
            signatureHex = "",
            authTagHex = IdentityManager.bytesToHex(Hkdf.extract(rootKey, "CONFIRM".toByteArray()))
        )
        stream.sendFrame(FramedSocketStream.STREAM_HANDSHAKE, confirmMsg.toJson().toByteArray(Charsets.UTF_8))

        val resolvedRecord = PeerRecord(
            peerId = resp.senderPeerId,
            displayName = resp.senderDisplayName,
            publicKeyHex = resp.senderEd25519PubHex,
            dhPublicKeyHex = resp.senderStaticDhPubHex ?: resp.senderEphemeralDhPubHex,
            addresses = remoteRecord.addresses,
            timestamp = resp.timestamp,
            signatureHex = resp.signatureHex
        )

        val session = DoubleRatchetSession(
            remotePeerId = resp.senderPeerId,
            initialRootKey = rootKey,
            isInitiator = true,
            remoteDhPublicKey = remoteEphemeralPub,
            localDhPrivateKey = ephemeralPriv
        )

        return Pair(resolvedRecord, session)
    }

    private fun performResponderHandshake(
        stream: FramedSocketStream,
        socket: Socket
    ): Pair<PeerRecord, DoubleRatchetSession>? {
        // Step 1: Read INIT
        val initFrame = stream.readFrame()
        if (initFrame.streamId != FramedSocketStream.STREAM_HANDSHAKE) return null
        val init = HandshakeEnvelope.fromJson(String(initFrame.payload, Charsets.UTF_8)) ?: return null
        if (init.step != HandshakeStep.INIT) return null
        if (!validateHandshakeIdentity(init)) return null

        val initiatorEdPub = IdentityManager.hexToBytes(init.senderEd25519PubHex)
        val sigValid = Curve25519.ed25519Verify(
            initiatorEdPub,
            init.serializeForSigning(),
            IdentityManager.hexToBytes(init.signatureHex)
        )
        if (!sigValid) return null

        val initiatorEphemeralPub = IdentityManager.hexToBytes(init.senderEphemeralDhPubHex)
        val initiatorStaticDhPub = if (!init.senderStaticDhPubHex.isNullOrEmpty()) {
            IdentityManager.hexToBytes(init.senderStaticDhPubHex)
        } else {
            initiatorEphemeralPub
        }

        // Step 2: Send RESPONSE
        val ephemeralPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephemeralPub = Curve25519.x25519PublicFromPrivate(ephemeralPriv)
        val timestamp = System.currentTimeMillis()

        val respMsg = HandshakeEnvelope(
            step = HandshakeStep.RESPONSE,
            senderPeerId = identityManager.peerId,
            senderDisplayName = identityManager.displayName,
            senderEd25519PubHex = identityManager.getPublicKeyHex(),
            senderStaticDhPubHex = identityManager.getDhPublicKeyHex(),
            senderEphemeralDhPubHex = IdentityManager.bytesToHex(ephemeralPub),
            timestamp = timestamp,
            signatureHex = IdentityManager.bytesToHex(
                identityManager.signData(
                    "${HandshakeStep.RESPONSE}|${identityManager.peerId}|${identityManager.displayName}|${identityManager.getPublicKeyHex()}|${identityManager.getDhPublicKeyHex()}|${IdentityManager.bytesToHex(ephemeralPub)}|$timestamp"
                        .toByteArray(Charsets.UTF_8)
                )
            )
        )
        stream.sendFrame(FramedSocketStream.STREAM_HANDSHAKE, respMsg.toJson().toByteArray(Charsets.UTF_8))

        // Step 3: Read CONFIRM
        val confirmFrame = stream.readFrame()
        if (confirmFrame.streamId != FramedSocketStream.STREAM_HANDSHAKE) return null
        val confirm = HandshakeEnvelope.fromJson(String(confirmFrame.payload, Charsets.UTF_8)) ?: return null
        if (confirm.step != HandshakeStep.CONFIRM) return null

        // Key agreement (Symmetric to Initiator):
        // ee = DH(our_ephemeral, their_ephemeral)
        val ee = safeDh(ephemeralPriv, initiatorEphemeralPub)
        // es = DH(our_static, their_ephemeral) -> matches initiator's DH(our_ephemeral, their_static)
        val es = safeDh(identityManager.x25519PrivateKey, initiatorEphemeralPub)
        // se = DH(our_ephemeral, their_static) -> matches initiator's DH(our_static, their_ephemeral)
        val se = safeDh(ephemeralPriv, initiatorStaticDhPub)

        val ikm = ee + es + se
        val rootKey = Hkdf.deriveKey("sol-mesh-v1".toByteArray(), ikm, "NoiseHandshakeRoot".toByteArray(), 32)

        // Verify the initiator's key confirmation (proves possession of its static DH key)
        if (confirm.senderPeerId != init.senderPeerId ||
            confirm.senderEd25519PubHex != init.senderEd25519PubHex) return null
        val expectedTag = Hkdf.extract(rootKey, "CONFIRM".toByteArray())
        val receivedTagHex = confirm.authTagHex ?: return null
        if (!isHex(receivedTagHex, expectedTag.size)) return null
        if (!MessageDigest.isEqual(expectedTag, IdentityManager.hexToBytes(receivedTagHex))) return null

        // Store peer's observed socket IP
        val remoteIp = socket.inetAddress?.hostAddress ?: ""
        val observedAddrs = if (remoteIp.isNotEmpty()) listOf("/ip4/$remoteIp/tcp/9091") else emptyList()

        val record = PeerRecord(
            peerId = init.senderPeerId,
            displayName = init.senderDisplayName,
            publicKeyHex = init.senderEd25519PubHex,
            dhPublicKeyHex = init.senderStaticDhPubHex ?: init.senderEphemeralDhPubHex,
            addresses = observedAddrs,
            timestamp = init.timestamp,
            signatureHex = init.signatureHex
        )

        val session = DoubleRatchetSession(
            remotePeerId = init.senderPeerId,
            initialRootKey = rootKey,
            isInitiator = false,
            remoteDhPublicKey = initiatorEphemeralPub,
            localDhPrivateKey = ephemeralPriv
        )

        return Pair(record, session)
    }

    private suspend fun runReaderLoop(conn: ActiveConnection) {
        val stream = conn.stream
        while (scope.isActive && stream.isConnected) {
            try {
                val frame = stream.readFrame()
                when (frame.streamId) {
                    FramedSocketStream.STREAM_CHAT -> {
                        val envelope = ChatEnvelope.fromJson(String(frame.payload, Charsets.UTF_8))
                        if (envelope != null) {
                            val decryptedPayload = decryptMessage(conn, envelope)
                            if (decryptedPayload != null) {
                                onMessageReceived(envelope, decryptedPayload)
                            }
                        }
                    }

                    FramedSocketStream.STREAM_FILE -> {
                        val envelope = decryptFileEnvelope(conn, frame.payload)
                        if (envelope != null) {
                            // Identity comes from the authenticated connection, never from the wire
                            onFileEnvelopeReceived(
                                envelope.copy(
                                    senderPeerId = conn.peerId,
                                    recipientPeerId = identityManager.peerId
                                )
                            )
                        }
                    }

                    FramedSocketStream.STREAM_PING -> {
                        // Ping received, respond with PONG
                        stream.sendFrame(FramedSocketStream.STREAM_PING, "PONG".toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                if (stream.isConnected) {
                    Log.w(TAG, "Reader loop exception with ${conn.peerId}: ${e.message}")
                }
                break
            }
        }

        // Connection terminated
        connections.remove(conn.peerId)
        setPeerState(conn.peerId, PeerConnectionState.DISCONNECTED, "Socket closed")
        try { conn.socket.close() } catch (_: Exception) {}
    }

    fun sendMessage(recipientPeerId: String, payload: ChatPlaintextPayload): Boolean {
        val conn = connections[recipientPeerId] ?: return false
        return try {
            val plaintextBytes = payload.toJson().toByteArray(Charsets.UTF_8)
            val encrypted = conn.session.encrypt(plaintextBytes)
            val envelope = ChatEnvelope(
                messageId = UUID.randomUUID().toString(),
                senderPeerId = identityManager.peerId,
                recipientPeerId = recipientPeerId,
                timestamp = System.currentTimeMillis(),
                sequenceNumber = encrypted.sequenceNumber,
                ratchetKeyHex = encrypted.ratchetKeyHex,
                nonceHex = encrypted.nonceHex,
                ciphertextHex = encrypted.ciphertextHex,
                previousChainLength = encrypted.previousChainLength
            )
            synchronized(conn.stream) {
                conn.stream.sendFrame(FramedSocketStream.STREAM_CHAT, envelope.toJson().toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending message to $recipientPeerId: ${e.message}", e)
            false
        }
    }

    fun sendFileEnvelope(recipientPeerId: String, envelope: FileEnvelope): Boolean {
        val conn = connections[recipientPeerId] ?: return false
        return try {
            // File commands and chunks are encrypted with the same Double Ratchet session as chat
            val enc = conn.session.encrypt(envelope.toJson().toByteArray(Charsets.UTF_8))
            val wrapper = JSONObject()
                .put("seq", enc.sequenceNumber)
                .put("rk", enc.ratchetKeyHex)
                .put("n", enc.nonceHex)
                .put("ct", enc.ciphertextHex)
                .put("pn", enc.previousChainLength)
            synchronized(conn.stream) {
                conn.stream.sendFrame(FramedSocketStream.STREAM_FILE, wrapper.toString().toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending file frame to $recipientPeerId: ${e.message}")
            false
        }
    }

    private fun decryptFileEnvelope(conn: ActiveConnection, payload: ByteArray): FileEnvelope? {
        return try {
            val w = JSONObject(String(payload, Charsets.UTF_8))
            val plain = conn.session.decrypt(
                DoubleRatchetSession.EncryptedEnvelope(
                    sequenceNumber = w.getLong("seq"),
                    ratchetKeyHex = w.getString("rk"),
                    nonceHex = w.getString("n"),
                    ciphertextHex = w.getString("ct"),
                    previousChainLength = w.optLong("pn", 0L)
                )
            )
            FileEnvelope.fromJson(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "File frame decryption error from ${conn.peerId}: ${e.message}")
            null
        }
    }

    private fun decryptMessage(conn: ActiveConnection, envelope: ChatEnvelope): ChatPlaintextPayload? {
        return try {
            val encryptedEnv = DoubleRatchetSession.EncryptedEnvelope(
                sequenceNumber = envelope.sequenceNumber,
                ratchetKeyHex = envelope.ratchetKeyHex,
                nonceHex = envelope.nonceHex,
                ciphertextHex = envelope.ciphertextHex,
                previousChainLength = envelope.previousChainLength
            )
            val decryptedBytes = conn.session.decrypt(encryptedEnv)
            ChatPlaintextPayload.fromJson(String(decryptedBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error from ${conn.peerId}: ${e.message}")
            null
        }
    }

    fun disconnect(peerId: String) {
        val conn = connections.remove(peerId) ?: return
        conn.readerJob?.cancel()
        try { conn.socket.close() } catch (_: Exception) {}
        setPeerState(peerId, PeerConnectionState.DISCONNECTED)
    }

    fun disconnectAll() {
        connections.values.forEach { conn ->
            conn.readerJob?.cancel()
            try { conn.socket.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}
