package com.example.messaging

import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MessageEntity
import com.example.p2p.ConnectionManager
import com.example.protocol.ChatEnvelope
import com.example.protocol.ChatMessageType
import com.example.protocol.ChatPlaintextPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * P2P Real-Time Encrypted Chat Manager.
 * Coordinates sending, receiving, delivery acknowledgments,
 * read receipts, and Room local persistence.
 */
class ChatManager(
    private val scope: CoroutineScope,
    private val database: AppDatabase,
    private val connectionManager: ConnectionManager
) {
    companion object {
        private const val TAG = "ChatManager"
    }

    /**
     * Sends a text message to a connected peer.
     */
    fun sendMessage(recipientPeerId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            val entity = MessageEntity(
                id = messageId,
                conversationPeerId = recipientPeerId,
                senderPeerId = "self",
                recipientPeerId = recipientPeerId,
                content = trimmed,
                timestamp = timestamp,
                deliveryStatus = "SENDING",
                isOutgoing = true,
                isRead = true
            )
            database.messageDao().insertMessage(entity)

            val payload = ChatPlaintextPayload(
                type = ChatMessageType.TEXT,
                text = trimmed,
                targetMessageId = messageId
            )

            val ok = connectionManager.sendMessage(recipientPeerId, payload)
            if (ok) {
                database.messageDao().updateDeliveryStatus(messageId, "SENT")
            } else {
                database.messageDao().updateDeliveryStatus(messageId, "FAILED")
            }
        }
    }

    /**
     * Handles incoming decrypted chat messages from the peer.
     * Zero read receipts / delivery indicators are dispatched across the wire.
     */
    fun handleIncomingMessage(envelope: ChatEnvelope, payload: ChatPlaintextPayload) {
        scope.launch(Dispatchers.IO) {
            when (payload.type) {
                ChatMessageType.TEXT -> {
                    val messageId = payload.targetMessageId ?: envelope.messageId
                    val entity = MessageEntity(
                        id = messageId,
                        conversationPeerId = envelope.senderPeerId,
                        senderPeerId = envelope.senderPeerId,
                        recipientPeerId = envelope.recipientPeerId,
                        content = payload.text,
                        timestamp = envelope.timestamp,
                        deliveryStatus = "RECEIVED",
                        isOutgoing = false,
                        isRead = false
                    )
                    database.messageDao().insertMessage(entity)
                }
                ChatMessageType.DELIVERY_ACK,
                ChatMessageType.READ_RECEIPT,
                ChatMessageType.TYPING -> {
                    // No-op: Wire indicators stripped for pure privacy
                }
            }
        }
    }

    fun markConversationAsRead(peerId: String) {
        scope.launch(Dispatchers.IO) {
            database.messageDao().markConversationAsRead(peerId)
        }
    }
}
