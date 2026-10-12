package com.example.p2p

import com.example.crypto.Curve25519
import com.example.crypto.IdentityManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cryptographically signed peer presence record stored in the Kademlia DHT.
 * Cannot be forged by malicious nodes because it contains an Ed25519 signature
 * over (peerId + timestamp + ttlSeconds + addresses).
 */
data class PeerRecord(
    val peerId: String,
    val displayName: String,
    val publicKeyHex: String,
    val dhPublicKeyHex: String,
    val addresses: List<String>,
    val timestamp: Long,
    val ttlSeconds: Long = 300,
    val signatureHex: String
) {
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        val expiryMillis = timestamp + (ttlSeconds * 1000)
        return currentTimeMillis > expiryMillis
    }

    fun serializeForSigning(): ByteArray {
        val sortedAddrs = addresses.sorted().joinToString(",")
        val raw = "$peerId|$displayName|$publicKeyHex|$dhPublicKeyHex|$sortedAddrs|$timestamp|$ttlSeconds"
        return raw.toByteArray(Charsets.UTF_8)
    }

    fun verifySignature(): Boolean {
        return try {
            val pubBytes = IdentityManager.hexToBytes(publicKeyHex)
            val sigBytes = IdentityManager.hexToBytes(signatureHex)
            val derivedPeerId = IdentityManager.derivePeerId(pubBytes)
            if (derivedPeerId != peerId) return false
            Curve25519.ed25519Verify(pubBytes, serializeForSigning(), sigBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("peerId", peerId)
        json.put("displayName", displayName)
        json.put("publicKeyHex", publicKeyHex)
        json.put("dhPublicKeyHex", dhPublicKeyHex)
        json.put("timestamp", timestamp)
        json.put("ttlSeconds", ttlSeconds)
        json.put("signatureHex", signatureHex)
        val arr = JSONArray()
        addresses.forEach { arr.put(it) }
        json.put("addresses", arr)
        return json.toString()
    }

    companion object {
        fun createSigned(
            identityManager: IdentityManager,
            addresses: List<String>,
            ttlSeconds: Long = 300
        ): PeerRecord {
            val timestamp = System.currentTimeMillis()
            val sortedAddrs = addresses.sorted().joinToString(",")
            val raw = "${identityManager.peerId}|${identityManager.displayName}|${identityManager.getPublicKeyHex()}|${identityManager.getDhPublicKeyHex()}|$sortedAddrs|$timestamp|$ttlSeconds"
            val signature = identityManager.signData(raw.toByteArray(Charsets.UTF_8))

            return PeerRecord(
                peerId = identityManager.peerId,
                displayName = identityManager.displayName,
                publicKeyHex = identityManager.getPublicKeyHex(),
                dhPublicKeyHex = identityManager.getDhPublicKeyHex(),
                addresses = addresses,
                timestamp = timestamp,
                ttlSeconds = ttlSeconds,
                signatureHex = IdentityManager.bytesToHex(signature)
            )
        }

        fun fromJson(jsonStr: String): PeerRecord? {
            return try {
                val json = JSONObject(jsonStr)
                val peerId = json.getString("peerId")
                val displayName = json.optString("displayName", "Peer")
                val publicKeyHex = json.getString("publicKeyHex")
                val dhPublicKeyHex = json.optString("dhPublicKeyHex", "")
                val timestamp = json.getLong("timestamp")
                val ttlSeconds = json.optLong("ttlSeconds", 300)
                val signatureHex = json.getString("signatureHex")
                val arr = json.getJSONArray("addresses")
                val addrs = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    addrs.add(arr.getString(i))
                }
                PeerRecord(
                    peerId = peerId,
                    displayName = displayName,
                    publicKeyHex = publicKeyHex,
                    dhPublicKeyHex = dhPublicKeyHex,
                    addresses = addrs,
                    timestamp = timestamp,
                    ttlSeconds = ttlSeconds,
                    signatureHex = signatureHex
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
