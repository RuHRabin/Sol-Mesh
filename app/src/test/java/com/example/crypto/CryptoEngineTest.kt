package com.example.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class CryptoEngineTest {

    @Test
    fun testEd25519SignAndVerify() {
        val (privateKey, publicKey) = Curve25519.ed25519GenerateKeyPair()
        assertEquals(32, privateKey.size)
        assertEquals(32, publicKey.size)

        val message = "Decentralized P2P message".toByteArray(Charsets.UTF_8)
        val signature = Curve25519.ed25519Sign(privateKey, message)
        assertEquals(64, signature.size)

        // Valid verification
        val isValid = Curve25519.ed25519Verify(publicKey, message, signature)
        assertTrue("Signature must verify with correct public key", isValid)

        // Tampered message rejection
        val tamperedMessage = "Decentralized P2P message tampered".toByteArray(Charsets.UTF_8)
        val isTamperedValid = Curve25519.ed25519Verify(publicKey, tamperedMessage, signature)
        assertFalse("Tampered message signature must be rejected", isTamperedValid)

        // Tampered signature rejection
        val tamperedSig = signature.copyOf()
        tamperedSig[0] = (tamperedSig[0].toInt() xor 0xFF).toByte()
        val isTamperedSigValid = Curve25519.ed25519Verify(publicKey, message, tamperedSig)
        assertFalse("Tampered signature must be rejected", isTamperedSigValid)
    }

    @Test
    fun testX25519DiffieHellmanKeyAgreement() {
        val random = SecureRandom()
        // Alice
        val alicePriv = ByteArray(32).also { random.nextBytes(it) }
        val alicePub = Curve25519.x25519PublicFromPrivate(alicePriv)

        // Bob
        val bobPriv = ByteArray(32).also { random.nextBytes(it) }
        val bobPub = Curve25519.x25519PublicFromPrivate(bobPriv)

        // Compute shared secret independently
        val sharedSecretAlice = Curve25519.x25519ScalarMult(alicePriv, bobPub)
        val sharedSecretBob = Curve25519.x25519ScalarMult(bobPriv, alicePub)

        assertEquals(32, sharedSecretAlice.size)
        assertEquals(32, sharedSecretBob.size)
        assertArrayEquals("Diffie-Hellman secrets must match on both sides", sharedSecretAlice, sharedSecretBob)
    }

    @Test
    fun testHkdfKeyDerivation() {
        val salt = "test-salt".toByteArray()
        val ikm = "shared-input-keying-material".toByteArray()
        val info = "Nexus-Context".toByteArray()

        val key1 = Hkdf.deriveKey(salt, ikm, info, 32)
        val key2 = Hkdf.deriveKey(salt, ikm, info, 32)

        assertEquals(32, key1.size)
        assertArrayEquals("HKDF must be deterministic", key1, key2)

        val keyDiffInfo = Hkdf.deriveKey(salt, ikm, "Different-Context".toByteArray(), 32)
        assertFalse("Different info must yield different key", key1.contentEquals(keyDiffInfo))
    }

    @Test
    fun testAeadEncryptionDecryptionAndTamperDetection() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = AeadCipher.generateNonce()
        val plaintext = "Secret payload directly between peers".toByteArray(Charsets.UTF_8)
        val ad = "header-metadata".toByteArray(Charsets.UTF_8)

        val ciphertext = AeadCipher.encrypt(key, nonce, plaintext, ad)
        val decrypted = AeadCipher.decrypt(key, nonce, ciphertext, ad)

        assertArrayEquals("Decrypted plaintext must match original", plaintext, decrypted)

        // Tamper with ciphertext -> must throw exception
        val corruptedCiphertext = ciphertext.copyOf()
        corruptedCiphertext[0] = (corruptedCiphertext[0].toInt() xor 0x01).toByte()
        try {
            AeadCipher.decrypt(key, nonce, corruptedCiphertext, ad)
            assertTrue("Tampered ciphertext must fail authentication", false)
        } catch (e: Exception) {
            // Expected AEAD authentication failure
            assertNotNull(e)
        }

        // Tamper with associated data -> must throw exception
        try {
            AeadCipher.decrypt(key, nonce, ciphertext, "corrupted-metadata".toByteArray())
            assertTrue("Tampered associated data must fail authentication", false)
        } catch (e: Exception) {
            assertNotNull(e)
        }
    }

    @Test
    fun testDoubleRatchetSessionBiDirectionalMessaging() {
        val sharedRootKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val random = SecureRandom()

        // Bob's prekey
        val bobDhPriv = ByteArray(32).also { random.nextBytes(it) }
        val bobDhPub = Curve25519.x25519PublicFromPrivate(bobDhPriv)

        val aliceSession = DoubleRatchetSession(
            remotePeerId = "bob",
            initialRootKey = sharedRootKey,
            isInitiator = true,
            remoteDhPublicKey = bobDhPub
        )

        val bobSession = DoubleRatchetSession(
            remotePeerId = "alice",
            initialRootKey = sharedRootKey,
            isInitiator = false,
            remoteDhPublicKey = aliceSession.ourRatchetPub,
            localDhPrivateKey = bobDhPriv
        )

        // 1. Alice -> Bob
        val msg1 = "Hello Bob from Alice via Double Ratchet".toByteArray(Charsets.UTF_8)
        val envelope1 = aliceSession.encrypt(msg1)
        val decrypted1 = bobSession.decrypt(envelope1)
        assertEquals(String(msg1, Charsets.UTF_8), String(decrypted1, Charsets.UTF_8))

        // 2. Bob -> Alice
        val msg2 = "Hi Alice, received securely!".toByteArray(Charsets.UTF_8)
        val envelope2 = bobSession.encrypt(msg2)
        val decrypted2 = aliceSession.decrypt(envelope2)
        assertEquals(String(msg2, Charsets.UTF_8), String(decrypted2, Charsets.UTF_8))

        // 3. Alice -> Bob second message
        val msg3 = "Forward secrecy confirmed.".toByteArray(Charsets.UTF_8)
        val envelope3 = aliceSession.encrypt(msg3)
        val decrypted3 = bobSession.decrypt(envelope3)
        assertEquals(String(msg3, Charsets.UTF_8), String(decrypted3, Charsets.UTF_8))
    }
}
