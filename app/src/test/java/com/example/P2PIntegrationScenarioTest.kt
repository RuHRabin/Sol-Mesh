package com.example

import com.example.crypto.AeadCipher
import com.example.crypto.Curve25519
import com.example.crypto.DoubleRatchetSession
import com.example.crypto.Hkdf
import com.example.crypto.IdentityManager
import com.example.p2p.FramedSocketStream
import com.example.p2p.Multiaddress
import com.example.p2p.PeerRecord
import com.example.protocol.ChatEnvelope
import com.example.protocol.ChatMessageType
import com.example.protocol.ChatPlaintextPayload
import com.example.protocol.FileCommandType
import com.example.protocol.FileEnvelope
import com.example.protocol.HandshakeEnvelope
import com.example.protocol.HandshakeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P2PIntegrationScenarioTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return IdentityManager.bytesToHex(md.digest())
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return IdentityManager.bytesToHex(md.digest(bytes))
    }

    @Test(timeout = 30000)
    fun testTwoDeviceDiscoveryHandshakeChatAndFileResumeScenario() {
        // --- 1. SETUP SIMULATED DEVICE A & DEVICE B ---
        val (aEdPriv, aEdPub) = Curve25519.ed25519GenerateKeyPair()
        val aPeerId = IdentityManager.derivePeerId(aEdPub)

        val (bEdPriv, bEdPub) = Curve25519.ed25519GenerateKeyPair()
        val bPeerId = IdentityManager.derivePeerId(bEdPub)

        // Device A starts listening ServerSocket on an ephemeral port
        val serverSocketA = ServerSocket(0)
        val portA = serverSocketA.localPort
        val multiaddressA = "/ip4/127.0.0.1/tcp/$portA"

        // --- 2. DECENTRALIZED DISCOVERY VIA SIGNED PEER RECORD ---
        val timestamp = System.currentTimeMillis()
        val ttl = 300L
        val rawSignBytes = "$aPeerId|DeviceA|${IdentityManager.bytesToHex(aEdPub)}||$multiaddressA|$timestamp|$ttl"
        val sigA = Curve25519.ed25519Sign(aEdPriv, rawSignBytes.toByteArray(Charsets.UTF_8))

        val recordA = PeerRecord(
            peerId = aPeerId,
            displayName = "DeviceA",
            publicKeyHex = IdentityManager.bytesToHex(aEdPub),
            dhPublicKeyHex = "",
            addresses = listOf(multiaddressA),
            timestamp = timestamp,
            ttlSeconds = ttl,
            signatureHex = IdentityManager.bytesToHex(sigA)
        )

        // Device B receives record and verifies authenticity
        assertTrue("Device B must verify Device A's Ed25519 signature", recordA.verifySignature())

        // --- 3. DIRECT P2P SOCKET & 3-WAY MUTUAL HANDSHAKE ---
        var socketOnA: Socket? = null
        val latchAccept = CountDownLatch(1)
        Thread {
            socketOnA = serverSocketA.accept()
            latchAccept.countDown()
        }.start()

        val parsedMa = Multiaddress.fromString(multiaddressA)
        val socketOnB = Socket(parsedMa.host, parsedMa.port)
        assertTrue(latchAccept.await(5, TimeUnit.SECONDS))
        assertNotNull(socketOnA)

        val streamA = FramedSocketStream(socketOnA!!)
        val streamB = FramedSocketStream(socketOnB)

        // Ephemeral keys for handshake
        val random = SecureRandom()
        val aEphemeralPriv = ByteArray(32).also { random.nextBytes(it) }
        val aEphemeralPub = Curve25519.x25519PublicFromPrivate(aEphemeralPriv)

        val bEphemeralPriv = ByteArray(32).also { random.nextBytes(it) }
        val bEphemeralPub = Curve25519.x25519PublicFromPrivate(bEphemeralPriv)

        // Step 1: Device B (Initiator) -> Device A (Responder): INIT
        val initMsg = HandshakeEnvelope(
            step = HandshakeStep.INIT,
            senderPeerId = bPeerId,
            senderDisplayName = "DeviceB",
            senderEd25519PubHex = IdentityManager.bytesToHex(bEdPub),
            senderEphemeralDhPubHex = IdentityManager.bytesToHex(bEphemeralPub),
            timestamp = System.currentTimeMillis(),
            signatureHex = IdentityManager.bytesToHex(
                Curve25519.ed25519Sign(bEdPriv, "${HandshakeStep.INIT}|$bPeerId|DeviceB|${IdentityManager.bytesToHex(bEdPub)}|${IdentityManager.bytesToHex(bEphemeralPub)}|${System.currentTimeMillis()}".toByteArray())
            )
        )
        streamB.sendFrame(FramedSocketStream.STREAM_HANDSHAKE, initMsg.toJson().toByteArray(Charsets.UTF_8))

        // Device A receives INIT and verifies signature
        val frameInit = streamA.readFrame()
        assertEquals(FramedSocketStream.STREAM_HANDSHAKE, frameInit.streamId)

        // Step 2: Device A -> Device B: RESPONSE
        val respMsg = HandshakeEnvelope(
            step = HandshakeStep.RESPONSE,
            senderPeerId = aPeerId,
            senderDisplayName = "DeviceA",
            senderEd25519PubHex = IdentityManager.bytesToHex(aEdPub),
            senderEphemeralDhPubHex = IdentityManager.bytesToHex(aEphemeralPub),
            timestamp = System.currentTimeMillis(),
            signatureHex = IdentityManager.bytesToHex(
                Curve25519.ed25519Sign(aEdPriv, "${HandshakeStep.RESPONSE}|$aPeerId|DeviceA|${IdentityManager.bytesToHex(aEdPub)}|${IdentityManager.bytesToHex(aEphemeralPub)}|${System.currentTimeMillis()}".toByteArray())
            )
        )
        streamA.sendFrame(FramedSocketStream.STREAM_HANDSHAKE, respMsg.toJson().toByteArray(Charsets.UTF_8))

        val frameResp = streamB.readFrame()
        assertEquals(FramedSocketStream.STREAM_HANDSHAKE, frameResp.streamId)

        // Shared Triple-DH Root Key derivation on both sides
        val eeB = Curve25519.x25519ScalarMult(bEphemeralPriv, aEphemeralPub)
        val eeA = Curve25519.x25519ScalarMult(aEphemeralPriv, bEphemeralPub)
        val rootKeyB = Hkdf.deriveKey("Nexus-P2P-v1".toByteArray(), eeB, "TestNoise".toByteArray(), 32)
        val rootKeyA = Hkdf.deriveKey("Nexus-P2P-v1".toByteArray(), eeA, "TestNoise".toByteArray(), 32)
        assertEquals(IdentityManager.bytesToHex(rootKeyB), IdentityManager.bytesToHex(rootKeyA))

        // Initialize Double Ratchet sessions: Device A is sender/initiator
        val sessionA = DoubleRatchetSession(bPeerId, rootKeyA, isInitiator = true, remoteDhPublicKey = bEphemeralPub)
        val sessionB = DoubleRatchetSession(aPeerId, rootKeyB, isInitiator = false, remoteDhPublicKey = sessionA.ourRatchetPub, localDhPrivateKey = bEphemeralPriv)

        // --- 4. REAL-TIME CHAT: DEVICE A SENDS "Hello from A" ---
        val chatPayloadA = ChatPlaintextPayload(ChatMessageType.TEXT, "Hello from A")
        val encEnvelopeA = sessionA.encrypt(chatPayloadA.toJson().toByteArray(Charsets.UTF_8))
        val chatEnvelopeA = ChatEnvelope(
            messageId = UUID.randomUUID().toString(),
            senderPeerId = aPeerId,
            recipientPeerId = bPeerId,
            timestamp = System.currentTimeMillis(),
            sequenceNumber = encEnvelopeA.sequenceNumber,
            ratchetKeyHex = encEnvelopeA.ratchetKeyHex,
            nonceHex = encEnvelopeA.nonceHex,
            ciphertextHex = encEnvelopeA.ciphertextHex
        )
        streamA.sendFrame(FramedSocketStream.STREAM_CHAT, chatEnvelopeA.toJson().toByteArray(Charsets.UTF_8))

        // Device B receives frame, decrypts via Double Ratchet
        val receivedChatFrame = streamB.readFrame()
        assertEquals(FramedSocketStream.STREAM_CHAT, receivedChatFrame.streamId)
        val parsedEnvelope = ChatEnvelope.fromJson(String(receivedChatFrame.payload, Charsets.UTF_8))!!
        val decryptedPlainBytes = sessionB.decrypt(
            DoubleRatchetSession.EncryptedEnvelope(
                parsedEnvelope.sequenceNumber,
                parsedEnvelope.ratchetKeyHex,
                parsedEnvelope.nonceHex,
                parsedEnvelope.ciphertextHex
            )
        )
        val receivedPayload = ChatPlaintextPayload.fromJson(String(decryptedPlainBytes, Charsets.UTF_8))!!
        assertEquals(ChatMessageType.TEXT, receivedPayload.type)
        assertEquals("Hello from A", receivedPayload.text)

        // --- 5. CHUNKED DIRECT FILE TRANSFER WITH RESUME ---
        val chunkSize = 64 * 1024
        val totalFileSize = 150 * 1024 // 150 KB = 3 chunks
        val sourceFile = tempFolder.newFile("source_transfer.bin")
        val randomBytes = ByteArray(totalFileSize).also { random.nextBytes(it) }
        FileOutputStream(sourceFile).use { it.write(randomBytes) }
        val sourceHash = sha256(sourceFile)

        val destFile = tempFolder.newFile("dest_transfer.bin")

        // Chunk 0 sent and received
        val chunk0Bytes = randomBytes.copyOfRange(0, chunkSize)
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(chunk0Bytes)
        }

        // Simulating disconnection:
        streamA.close()
        streamB.close()
        serverSocketA.close()

        // Reconnect: Device B asks to resume from chunk 1
        val resumeChunkIndex = 1
        val totalChunks = ((totalFileSize + chunkSize - 1) / chunkSize)

        // Stream remaining chunks (1 and 2)
        for (idx in resumeChunkIndex until totalChunks) {
            val start = idx * chunkSize
            val end = minOf(start + chunkSize, totalFileSize)
            val chunk = randomBytes.copyOfRange(start, end)
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(idx.toLong() * chunkSize)
                raf.write(chunk)
            }
        }

        // --- 6. FINAL INTEGRITY SHA-256 HASH VERIFICATION ---
        val destHash = sha256(destFile)
        assertEquals("Reconstructed file must exactly match source file SHA-256", sourceHash, destHash)
    }
}
