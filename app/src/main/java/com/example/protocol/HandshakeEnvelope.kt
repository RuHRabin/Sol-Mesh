package com.example.protocol

import org.json.JSONObject

enum class HandshakeStep {
    INIT,
    RESPONSE,
    CONFIRM,
    REJECT
}

data class HandshakeEnvelope(
    val step: HandshakeStep,
    val senderPeerId: String,
    val senderDisplayName: String,
    val senderEd25519PubHex: String,
    val senderEphemeralDhPubHex: String,
    val timestamp: Long,
    val signatureHex: String,
    val senderStaticDhPubHex: String? = null,
    val authTagHex: String? = null,
    val rejectReason: String? = null
) {
    fun serializeForSigning(): ByteArray {
        val staticDh = senderStaticDhPubHex ?: ""
        val raw = "$step|$senderPeerId|$senderDisplayName|$senderEd25519PubHex|$staticDh|$senderEphemeralDhPubHex|$timestamp"
        return raw.toByteArray(Charsets.UTF_8)
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("step", step.name)
        json.put("senderPeerId", senderPeerId)
        json.put("senderDisplayName", senderDisplayName)
        json.put("senderEd25519PubHex", senderEd25519PubHex)
        json.put("senderEphemeralDhPubHex", senderEphemeralDhPubHex)
        senderStaticDhPubHex?.let { json.put("senderStaticDhPubHex", it) }
        json.put("timestamp", timestamp)
        json.put("signatureHex", signatureHex)
        authTagHex?.let { json.put("authTagHex", it) }
        rejectReason?.let { json.put("rejectReason", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): HandshakeEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                HandshakeEnvelope(
                    step = HandshakeStep.valueOf(json.getString("step")),
                    senderPeerId = json.getString("senderPeerId"),
                    senderDisplayName = json.optString("senderDisplayName", "Peer"),
                    senderEd25519PubHex = json.getString("senderEd25519PubHex"),
                    senderEphemeralDhPubHex = json.getString("senderEphemeralDhPubHex"),
                    timestamp = json.getLong("timestamp"),
                    signatureHex = json.getString("signatureHex"),
                    senderStaticDhPubHex = if (json.has("senderStaticDhPubHex")) json.getString("senderStaticDhPubHex") else null,
                    authTagHex = if (json.has("authTagHex")) json.getString("authTagHex") else null,
                    rejectReason = if (json.has("rejectReason")) json.getString("rejectReason") else null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
