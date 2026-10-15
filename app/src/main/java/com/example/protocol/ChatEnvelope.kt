package com.example.protocol

import org.json.JSONObject

enum class ChatMessageType {
    TEXT,
    DELIVERY_ACK,
    READ_RECEIPT,
    TYPING
}

data class ChatPlaintextPayload(
    val type: ChatMessageType,
    val text: String,
    val targetMessageId: String? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type.name)
        json.put("text", text)
        targetMessageId?.let { json.put("targetMessageId", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ChatPlaintextPayload? {
            return try {
                val json = JSONObject(jsonStr)
                ChatPlaintextPayload(
                    type = ChatMessageType.valueOf(json.getString("type")),
                    text = json.optString("text", ""),
                    targetMessageId = if (json.has("targetMessageId")) json.getString("targetMessageId") else null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Encrypted wire envelope for /p2p-chat/1.0.0 stream.
 * Plaintext chat payload is encrypted via Double Ratchet / AES-256-GCM.
 */
data class ChatEnvelope(
    val version: String = "1.0.0",
    val messageId: String,
    val senderPeerId: String,
    val recipientPeerId: String,
    val timestamp: Long,
    val sequenceNumber: Long,
    val ratchetKeyHex: String,
    val nonceHex: String,
    val ciphertextHex: String,
    val previousChainLength: Long = 0
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("messageId", messageId)
        json.put("senderPeerId", senderPeerId)
        json.put("recipientPeerId", recipientPeerId)
        json.put("timestamp", timestamp)
        json.put("sequenceNumber", sequenceNumber)
        json.put("ratchetKeyHex", ratchetKeyHex)
        json.put("nonceHex", nonceHex)
        json.put("ciphertextHex", ciphertextHex)
        json.put("previousChainLength", previousChainLength)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ChatEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                ChatEnvelope(
                    version = json.optString("version", "1.0.0"),
                    messageId = json.getString("messageId"),
                    senderPeerId = json.getString("senderPeerId"),
                    recipientPeerId = json.getString("recipientPeerId"),
                    timestamp = json.getLong("timestamp"),
                    sequenceNumber = json.getLong("sequenceNumber"),
                    ratchetKeyHex = json.getString("ratchetKeyHex"),
                    nonceHex = json.getString("nonceHex"),
                    ciphertextHex = json.getString("ciphertextHex"),
                    previousChainLength = json.optLong("previousChainLength", 0L)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
