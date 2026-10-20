package com.example.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom

/** A short text message with its id and time, as carried inside a sealed offline message. */
class OfflineMessage(val id: String, val timestamp: Long, val text: String) {

    fun encode(): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(idBytes.size in 1..255) { "Message id must be 1 to 255 bytes" }
        require(textBytes.size <= SealedBox.MAX_TEXT_BYTES) { "Message is too long for offline delivery" }
        return ByteBuffer.allocate(1 + idBytes.size + 8 + textBytes.size)
            .put(idBytes.size.toByte())
            .put(idBytes)
            .putLong(timestamp)
            .put(textBytes)
            .array()
    }

    companion object {
        fun decode(bytes: ByteArray): OfflineMessage? {
            return try {
                val buf = ByteBuffer.wrap(bytes)
                val idLen = buf.get().toInt() and 0xFF
                if (idLen == 0 || buf.remaining() < idLen + 8) return null
                val idBytes = ByteArray(idLen)
                buf.get(idBytes)
                val timestamp = buf.getLong()
                val textBytes = ByteArray(buf.remaining())
                buf.get(textBytes)
                if (textBytes.size > SealedBox.MAX_TEXT_BYTES) return null
                OfflineMessage(String(idBytes, Charsets.UTF_8), timestamp, String(textBytes, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * End-to-end encryption for messages that are left on a server while the recipient is offline.
 *
 * The live chat uses a fresh Double Ratchet session per connection, which cannot exist while the other
 * phone is offline. So offline messages are sealed to the recipient's long-term X25519 key instead:
 *
 *  - a fresh ephemeral X25519 key per message, ECDH with the recipient's key, HKDF, AES-256-GCM;
 *  - the sender signs the whole thing (Ed25519), so the recipient knows who it is from and nobody
 *    can redirect it to another recipient.
 *
 * Only the recipient can open it. Weaker than the live ratchet in one way: if the recipient's long-term
 * key is stolen later, messages recorded earlier could be read (the server deletes them after delivery).
 *
 * Wire format: [version 1][sender Ed25519 public key 32][ephemeral X25519 public key 32]
 *              [nonce 12][signature 64][ciphertext + 16-byte tag]
 *
 * Pure Kotlin on purpose (no Android imports) so it can be unit tested on a laptop.
 */
object SealedBox {
    const val VERSION: Byte = 1
    const val MAX_TEXT_BYTES = 8000

    private const val PUB = 32
    private const val NONCE = AeadCipher.NONCE_LENGTH_BYTES
    private const val SIG = 64
    private const val TAG = 16
    private const val HEADER = 1 + PUB + PUB + NONCE + SIG
    private val INFO = "SolMesh sealed message v1".toByteArray(Charsets.US_ASCII)
    private val SIG_CONTEXT = "SolMeshSealed1".toByteArray(Charsets.US_ASCII)

    class Opened(val senderPublicKey: ByteArray, val message: OfflineMessage)

    fun seal(
        senderEdPrivate: ByteArray,
        senderEdPublic: ByteArray,
        recipientDhPublic: ByteArray,
        message: OfflineMessage
    ): ByteArray {
        require(senderEdPublic.size == PUB && recipientDhPublic.size == PUB) { "Keys must be 32 bytes" }

        val ephPrivate = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephPublic = Curve25519.x25519PublicFromPrivate(ephPrivate)
        val shared = Curve25519.x25519ScalarMult(ephPrivate, recipientDhPublic)
        require(shared.any { it.toInt() != 0 }) { "Unusable recipient key" }

        val key = Hkdf.deriveKey(ephPublic + recipientDhPublic, shared, INFO, 32)
        val nonce = AeadCipher.generateNonce()
        val aad = byteArrayOf(VERSION) + senderEdPublic + ephPublic + recipientDhPublic
        val ciphertext = AeadCipher.encrypt(key, nonce, message.encode(), aad)

        val signature = Curve25519.ed25519Sign(
            senderEdPrivate,
            signedBytes(senderEdPublic, ephPublic, recipientDhPublic, nonce, ciphertext)
        )
        return byteArrayOf(VERSION) + senderEdPublic + ephPublic + nonce + signature + ciphertext
    }

    /** Returns null if the data is damaged, not for this recipient, or not genuinely from the claimed sender. */
    fun open(sealed: ByteArray, recipientDhPrivate: ByteArray, recipientDhPublic: ByteArray): Opened? {
        return try {
            if (sealed.size < HEADER + TAG || sealed[0] != VERSION) return null
            var pos = 1
            val senderPub = sealed.copyOfRange(pos, pos + PUB); pos += PUB
            val ephPub = sealed.copyOfRange(pos, pos + PUB); pos += PUB
            val nonce = sealed.copyOfRange(pos, pos + NONCE); pos += NONCE
            val signature = sealed.copyOfRange(pos, pos + SIG); pos += SIG
            val ciphertext = sealed.copyOfRange(pos, sealed.size)

            if (!Curve25519.ed25519Verify(
                    senderPub,
                    signedBytes(senderPub, ephPub, recipientDhPublic, nonce, ciphertext),
                    signature
                )
            ) return null

            val shared = Curve25519.x25519ScalarMult(recipientDhPrivate, ephPub)
            if (shared.all { it.toInt() == 0 }) return null
            val key = Hkdf.deriveKey(ephPub + recipientDhPublic, shared, INFO, 32)
            val aad = byteArrayOf(VERSION) + senderPub + ephPub + recipientDhPublic
            val plaintext = AeadCipher.decrypt(key, nonce, ciphertext, aad)
            val message = OfflineMessage.decode(plaintext) ?: return null
            Opened(senderPub, message)
        } catch (_: Exception) {
            null
        }
    }

    private fun signedBytes(
        senderPub: ByteArray,
        ephPub: ByteArray,
        recipientDhPub: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray
    ): ByteArray = SIG_CONTEXT + byteArrayOf(VERSION) + senderPub + ephPub + recipientDhPub + nonce + ciphertext
}
