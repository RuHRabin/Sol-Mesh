package com.example.crypto

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Long-term Cryptographic Identity Manager.
 * Generates and manages the node's Ed25519 Identity Key, paired X25519 DH key,
 * deterministic PeerID, and out-of-band verification safety numbers.
 */
class IdentityManager(
    private val context: Context,
    private val keyStoreManager: KeyStoreManager = KeyStoreManager(context)
) {
    companion object {
        private const val KEY_ED25519_PRIV = "id_ed25519_private"
        private const val KEY_ED25519_PUB = "id_ed25519_public"
        private const val KEY_X25519_PRIV = "id_x25519_private"
        private const val KEY_X25519_PUB = "id_x25519_public"
        private const val KEY_DISPLAY_NAME = "id_display_name"

        fun derivePeerId(ed25519PublicKey: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(ed25519PublicKey)
            // Libp2p multihash: 0x12 (sha2-256), 0x20 (32 bytes length) + 32-byte digest
            val multihash = ByteArray(34)
            multihash[0] = 0x12
            multihash[1] = 0x20
            System.arraycopy(hash, 0, multihash, 2, 32)
            return Base58.encode(multihash)
        }

        fun hexToBytes(hex: String): ByteArray {
            val s = hex.trim().replace(":", "").replace(" ", "")
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                        Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        /**
         * Calculates mutual Safety Number fingerprint between two peers (e.g. Signal style)
         * Formatted as 12 groups of 5 decimal digits.
         */
        fun computeSafetyNumber(ourPubKey: ByteArray, theirPubKey: ByteArray): String {
            // Sort keys deterministically
            val (first, second) = if (compareBytes(ourPubKey, theirPubKey) < 0) {
                Pair(ourPubKey, theirPubKey)
            } else {
                Pair(theirPubKey, ourPubKey)
            }

            val salt = "Nexus-Safety-Number-v1".toByteArray(Charsets.UTF_8)
            val ikm = first + second
            val derived = Hkdf.deriveKey(salt, ikm, "Fingerprint".toByteArray(Charsets.UTF_8), 32)

            val digits = StringBuilder()
            for (i in 0 until 12) {
                val offset = (i * 2) % 30
                val value = ((derived[offset].toInt() and 0xFF) shl 8) or
                        (derived[offset + 1].toInt() and 0xFF)
                val chunk = (value % 100000).toString().padStart(5, '0')
                digits.append(chunk)
                if (i < 11) digits.append(" ")
            }
            return digits.toString()
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }
    }

    val ed25519PrivateKey: ByteArray
    val ed25519PublicKey: ByteArray
    val x25519PrivateKey: ByteArray
    val x25519PublicKey: ByteArray
    val peerId: String

    var displayName: String
        get() = context.getSharedPreferences("sol_mesh_profile", Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "sol-mesh node") ?: "sol-mesh node"
        set(value) = context.getSharedPreferences("sol_mesh_profile", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, value)
            .apply()

    init {
        val existingEdPriv = try {
            keyStoreManager.loadDecrypted(KEY_ED25519_PRIV)
        } catch (_: Exception) {
            null
        }

        if (existingEdPriv != null && existingEdPriv.size == 32) {
            ed25519PrivateKey = existingEdPriv
            ed25519PublicKey = keyStoreManager.loadDecrypted(KEY_ED25519_PUB)
                ?: Curve25519.ed25519PublicKey(existingEdPriv)
            x25519PrivateKey = keyStoreManager.loadDecrypted(KEY_X25519_PRIV)
                ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            x25519PublicKey = keyStoreManager.loadDecrypted(KEY_X25519_PUB)
                ?: Curve25519.x25519PublicFromPrivate(x25519PrivateKey)
        } else {
            // Generate fresh long-term keys
            val (edPriv, edPub) = Curve25519.ed25519GenerateKeyPair()
            ed25519PrivateKey = edPriv
            ed25519PublicKey = edPub

            val xPriv = ByteArray(32)
            SecureRandom().nextBytes(xPriv)
            val xPub = Curve25519.x25519PublicFromPrivate(xPriv)
            x25519PrivateKey = xPriv
            x25519PublicKey = xPub

            // Persist encrypted with Android Keystore
            keyStoreManager.saveEncrypted(KEY_ED25519_PRIV, ed25519PrivateKey)
            keyStoreManager.saveEncrypted(KEY_ED25519_PUB, ed25519PublicKey)
            keyStoreManager.saveEncrypted(KEY_X25519_PRIV, x25519PrivateKey)
            keyStoreManager.saveEncrypted(KEY_X25519_PUB, x25519PublicKey)
        }

        peerId = derivePeerId(ed25519PublicKey)
    }

    fun signData(data: ByteArray): ByteArray {
        return Curve25519.ed25519Sign(ed25519PrivateKey, data)
    }

    fun verifyData(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return Curve25519.ed25519Verify(publicKey, data, signature)
    }

    fun getPublicKeyHex(): String = bytesToHex(ed25519PublicKey)
    fun getDhPublicKeyHex(): String = bytesToHex(x25519PublicKey)

    fun getFormattedFingerprint(): String {
        val hex = getPublicKeyHex().uppercase(Locale.ROOT)
        return hex.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
    }
}
