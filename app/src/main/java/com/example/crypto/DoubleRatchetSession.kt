package com.example.crypto

import java.security.SecureRandom

/**
 * Signal-style Double Ratchet session implementation for application-layer E2EE.
 * Provides Perfect Forward Secrecy (PFS) and Break-in Recovery.
 */
class DoubleRatchetSession(
    val remotePeerId: String,
    initialRootKey: ByteArray,
    val isInitiator: Boolean,
    remoteDhPublicKey: ByteArray? = null,
    localDhPrivateKey: ByteArray? = null
) {
    private var rootKey: ByteArray = initialRootKey.copyOf()
    private var sendChainKey: ByteArray? = null
    private var recvChainKey: ByteArray? = null

    var ourRatchetPriv: ByteArray = localDhPrivateKey?.copyOf() ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
    var ourRatchetPub: ByteArray = Curve25519.x25519PublicFromPrivate(ourRatchetPriv)
    var theirRatchetPub: ByteArray? = remoteDhPublicKey?.copyOf()

    var sendSeq: Long = 0
    var recvSeq: Long = 0

    // Number of messages sent on the previous sending chain (sent as "PN" so the peer can
    // derive skipped keys of the old chain before performing a DH ratchet step).
    private var previousSendLength: Long = 0

    companion object {
        /** Maximum number of message keys that may be skipped in one chain (DoS bound). */
        const val MAX_SKIP = 1000L
        /** Maximum number of skipped message keys kept in memory. */
        const val MAX_STORED_SKIPPED = 2000
    }

    // Skipped message keys cache to handle out-of-order delivery: (ratchetPubHex + seq) -> MessageKey
    private val skippedMessageKeys = LinkedHashMap<String, ByteArray>()

    init {
        if (isInitiator && remoteDhPublicKey != null) {
            // Initiator initializes DH ratchet with receiver's pre-known / handshake public key
            val dhSecret = Curve25519.x25519ScalarMult(ourRatchetPriv, remoteDhPublicKey)
            val derived = Hkdf.deriveKey(rootKey, dhSecret, "DHRatchetRoot".toByteArray(), 64)
            rootKey = derived.copyOfRange(0, 32)
            sendChainKey = derived.copyOfRange(32, 64)
        }
    }

    data class EncryptedEnvelope(
        val sequenceNumber: Long,
        val ratchetKeyHex: String,
        val nonceHex: String,
        val ciphertextHex: String,
        val previousChainLength: Long = 0
    )

    @Synchronized
    fun encrypt(plaintext: ByteArray): EncryptedEnvelope {
        val currentSendChain = sendChainKey ?: run {
            // Sender chain not set: the responder is speaking before it has received anything.
            val theirPub = theirRatchetPub
            if (theirPub != null) {
                if (recvChainKey == null) {
                    // The initiator is already sending on the chain derived from
                    // DH(initiator ephemeral, responder ephemeral). Derive it here as our receive
                    // chain (and advance the root key, exactly like the initiator did) so the
                    // initiator's messages can still be decrypted, then ratchet with a fresh key.
                    val dhInit = Curve25519.x25519ScalarMult(ourRatchetPriv, theirPub)
                    val init = Hkdf.deriveKey(rootKey, dhInit, "DHRatchetRoot".toByteArray(), 64)
                    rootKey = init.copyOfRange(0, 32)
                    recvChainKey = init.copyOfRange(32, 64)
                    recvSeq = 0
                }
                val newPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
                ourRatchetPriv = newPriv
                ourRatchetPub = Curve25519.x25519PublicFromPrivate(newPriv)
            }
            val dhSecret = theirPub?.let { Curve25519.x25519ScalarMult(ourRatchetPriv, it) }
                ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            val derived = Hkdf.deriveKey(rootKey, dhSecret, "DHRatchetRoot".toByteArray(), 64)
            rootKey = derived.copyOfRange(0, 32)
            val newChain = derived.copyOfRange(32, 64)
            sendChainKey = newChain
            newChain
        }

        // Advance symmetric sending chain
        val messageKey = Hkdf.deriveKey(currentSendChain, "MessageKey".toByteArray(), null, 32)
        sendChainKey = Hkdf.deriveKey(currentSendChain, "NextChainKey".toByteArray(), null, 32)

        val nonce = AeadCipher.generateNonce()
        val ad = "${sendSeq}:${IdentityManager.bytesToHex(ourRatchetPub)}".toByteArray(Charsets.UTF_8)
        val ciphertext = AeadCipher.encrypt(messageKey, nonce, plaintext, ad)

        val envelope = EncryptedEnvelope(
            sequenceNumber = sendSeq,
            ratchetKeyHex = IdentityManager.bytesToHex(ourRatchetPub),
            nonceHex = IdentityManager.bytesToHex(nonce),
            ciphertextHex = IdentityManager.bytesToHex(ciphertext),
            previousChainLength = previousSendLength
        )
        sendSeq++
        return envelope
    }

    private class State(
        val rootKey: ByteArray,
        val sendChainKey: ByteArray?,
        val recvChainKey: ByteArray?,
        val ourPriv: ByteArray,
        val ourPub: ByteArray,
        val theirPub: ByteArray?,
        val sendSeq: Long,
        val recvSeq: Long,
        val previousSendLength: Long,
        val skipped: LinkedHashMap<String, ByteArray>
    )

    private fun snapshot() = State(
        rootKey, sendChainKey, recvChainKey, ourRatchetPriv, ourRatchetPub, theirRatchetPub,
        sendSeq, recvSeq, previousSendLength, LinkedHashMap(skippedMessageKeys)
    )

    private fun restore(st: State) {
        rootKey = st.rootKey
        sendChainKey = st.sendChainKey
        recvChainKey = st.recvChainKey
        ourRatchetPriv = st.ourPriv
        ourRatchetPub = st.ourPub
        theirRatchetPub = st.theirPub
        sendSeq = st.sendSeq
        recvSeq = st.recvSeq
        previousSendLength = st.previousSendLength
        skippedMessageKeys.clear()
        skippedMessageKeys.putAll(st.skipped)
    }

    /** Derives and stores message keys of the current receive chain up to (excluding) [until]. */
    private fun skipMessageKeys(ratchetKeyHex: String, until: Long) {
        var chain = recvChainKey ?: return
        if (until - recvSeq > MAX_SKIP) throw IllegalStateException("Too many skipped messages")
        while (recvSeq < until) {
            val mk = Hkdf.deriveKey(chain, "MessageKey".toByteArray(), null, 32)
            chain = Hkdf.deriveKey(chain, "NextChainKey".toByteArray(), null, 32)
            skippedMessageKeys["$ratchetKeyHex:$recvSeq"] = mk
            while (skippedMessageKeys.size > MAX_STORED_SKIPPED) {
                skippedMessageKeys.remove(skippedMessageKeys.keys.first())
            }
            recvSeq++
        }
        recvChainKey = chain
    }

    /**
     * Decrypts an envelope. Session state is only committed if authentication succeeds;
     * a forged or corrupted message leaves the session untouched.
     */
    @Synchronized
    fun decrypt(envelope: EncryptedEnvelope): ByteArray {
        val ratchetKeyBytes = IdentityManager.hexToBytes(envelope.ratchetKeyHex)
        val nonce = IdentityManager.hexToBytes(envelope.nonceHex)
        val ciphertext = IdentityManager.hexToBytes(envelope.ciphertextHex)
        require(ratchetKeyBytes.size == 32) { "Invalid ratchet key" }
        require(nonce.size == AeadCipher.NONCE_LENGTH_BYTES) { "Invalid nonce" }
        require(envelope.sequenceNumber >= 0) { "Invalid sequence number" }
        require(envelope.previousChainLength >= 0) { "Invalid previous chain length" }
        val ad = "${envelope.sequenceNumber}:${envelope.ratchetKeyHex}".toByteArray(Charsets.UTF_8)

        val saved = snapshot()
        try {
            val cacheKey = "${envelope.ratchetKeyHex}:${envelope.sequenceNumber}"

            // Out-of-order message whose key was stored earlier
            val cached = skippedMessageKeys[cacheKey]
            if (cached != null) {
                val plaintext = AeadCipher.decrypt(cached, nonce, ciphertext, ad)
                skippedMessageKeys.remove(cacheKey)
                return plaintext
            }

            // DH ratchet step if the remote ratchet key changed or no receive chain exists yet
            if (recvChainKey == null || theirRatchetPub == null || !theirRatchetPub!!.contentEquals(ratchetKeyBytes)) {
                // Store the remaining keys of the old receive chain before replacing it
                val oldTheirHex = theirRatchetPub?.let { IdentityManager.bytesToHex(it) }
                if (recvChainKey != null && oldTheirHex != null) {
                    skipMessageKeys(oldTheirHex, envelope.previousChainLength)
                }
                theirRatchetPub = ratchetKeyBytes.copyOf()

                // 1. Recv DH ratchet
                val dhRecv = Curve25519.x25519ScalarMult(ourRatchetPriv, theirRatchetPub!!)
                val derivedRecv = Hkdf.deriveKey(rootKey, dhRecv, "DHRatchetRoot".toByteArray(), 64)
                rootKey = derivedRecv.copyOfRange(0, 32)
                recvChainKey = derivedRecv.copyOfRange(32, 64)
                recvSeq = 0

                // 2. New local ephemeral ratchet keypair for subsequent sends
                val newPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
                ourRatchetPriv = newPriv
                ourRatchetPub = Curve25519.x25519PublicFromPrivate(newPriv)

                // 3. Send DH ratchet
                val dhSend = Curve25519.x25519ScalarMult(ourRatchetPriv, theirRatchetPub!!)
                val derivedSend = Hkdf.deriveKey(rootKey, dhSend, "DHRatchetRoot".toByteArray(), 64)
                rootKey = derivedSend.copyOfRange(0, 32)
                sendChainKey = derivedSend.copyOfRange(32, 64)
                previousSendLength = sendSeq
                sendSeq = 0
            }

            require(envelope.sequenceNumber >= recvSeq) { "Duplicate or replayed message" }
            skipMessageKeys(envelope.ratchetKeyHex, envelope.sequenceNumber)

            val chain = recvChainKey ?: error("No receive chain available for peer $remotePeerId")
            val messageKey = Hkdf.deriveKey(chain, "MessageKey".toByteArray(), null, 32)
            val nextChain = Hkdf.deriveKey(chain, "NextChainKey".toByteArray(), null, 32)

            val plaintext = AeadCipher.decrypt(messageKey, nonce, ciphertext, ad)
            recvChainKey = nextChain
            recvSeq++
            return plaintext
        } catch (e: Exception) {
            restore(saved)
            throw e
        }
    }
}
