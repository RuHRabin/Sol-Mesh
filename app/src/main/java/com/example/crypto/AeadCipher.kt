package com.example.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated Encryption with Associated Data (AEAD) using AES-256-GCM.
 * Hardware-accelerated on Android devices with 128-bit authentication tag.
 */
object AeadCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_LENGTH_BITS = 128
    const val NONCE_LENGTH_BYTES = 12

    private val secureRandom = SecureRandom()

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * @param key 32-byte secret key
     * @param nonce 12-byte unique initialization vector
     * @param plaintext raw data to encrypt
     * @param associatedData optional authenticated non-encrypted header
     * @return ciphertext including appended 16-byte authentication tag
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(nonce.size == NONCE_LENGTH_BYTES) { "AES-GCM requires a 12-byte nonce" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }

        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts and verifies ciphertext using AES-256-GCM.
     * Throws GeneralSecurityException / AEADBadTagException if authentication fails.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(key.size == 32) { "AES-256 requires a 32-byte key" }
        require(nonce.size == NONCE_LENGTH_BYTES) { "AES-GCM requires a 12-byte nonce" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }

        return cipher.doFinal(ciphertextWithTag)
    }
}
