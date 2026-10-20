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
        private const val RETRY_WINDOW_MS = 24L * 60 * 60 * 1000
    }

    /** Called with (peerId, text) for every new incoming text message (not for duplicates). */
    @Volatile
    var onIncomingMessage: ((String, String) -> Unit)? = null

    /**
     * Leaves a message in the server mailbox when the person is not connected: (peerId, messageId, text,
     * timestamp) -> true if the server took it. Set by the node; null when no server is configured.
     */
    @Volatile
    var offlineSender: (suspend (String, String, String, Long) -> Boolean)? = null

    private suspend fun sendViaMailbox(peerId: String, messageId: String, text: String, timestamp: Long): Boolean {
        val sender = offlineSender ?: return false
        return try {
            sender(peerId, messageId, text, timestamp)
        } catch (e: Exception) {
            false
        }
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

            val ok = connectionManager.sendMessage(recipientPeerId, payload) ||
                sendViaMailbox(recipientPeerId, messageId, trimmed, timestamp)
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
                    val rowId = database.messageDao().insertMessage(entity)
                    if (rowId != -1L) {
                        onIncomingMessage?.invoke(envelope.senderPeerId, payload.text)
                    }
                }
                ChatMessageType.DELIVERY_ACK,
                ChatMessageType.READ_RECEIPT,
                ChatMessageType.TYPING -> {
                    // No-op: Wire indicators stripped for pure privacy
                }
            }
        }
    }

    /**
     * Stores a message that arrived through the server mailbox. Returns true when it is safe for the server
     * to delete it (stored now, or already stored earlier).
     */
    suspend fun receiveOfflineMessage(senderPeerId: String, messageId: String, text: String, timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = messageId,
            conversationPeerId = senderPeerId,
            senderPeerId = senderPeerId,
            recipientPeerId = "self",
            content = text,
            // the time the sender wrote it, but never from the future and not absurdly old
            timestamp = timestamp.coerceIn(now - 30L * 24 * 60 * 60 * 1000, now),
            deliveryStatus = "RECEIVED",
            isOutgoing = false,
            isRead = false
        )
        val rowId = database.messageDao().insertMessage(entity)
        if (rowId != -1L) {
            onIncomingMessage?.invoke(senderPeerId, text)
        }
        return true
    }

    /**
     * Retries one message that failed (or got stuck while sending).
     * Safe to repeat: the receiver ignores a message id it already has.
     */
    fun retryMessage(messageId: String) {
        scope.launch(Dispatchers.IO) { resend(messageId) }
    }

    /**
     * Resends recent unsent messages for [peerId], oldest first. Called when the peer reconnects,
     * so messages written while offline are delivered automatically.
     */
    fun retryUnsent(peerId: String) {
        scope.launch(Dispatchers.IO) {
            val since = System.currentTimeMillis() - RETRY_WINDOW_MS
            for (message in database.messageDao().getUnsentOutgoing(peerId, since)) {
                // stop at the first failure: the connection dropped again, we retry on the next reconnect
                if (!resend(message.id)) break
            }
        }
    }

    private suspend fun resend(messageId: String): Boolean {
        val message = database.messageDao().getMessageById(messageId) ?: return false
        if (!message.isOutgoing) return false
        if (message.deliveryStatus != "FAILED" && message.deliveryStatus != "SENDING") return false

        database.messageDao().updateDeliveryStatus(messageId, "SENDING")
        val payload = ChatPlaintextPayload(
            type = ChatMessageType.TEXT,
            text = message.content,
            targetMessageId = messageId
        )
        val ok = connectionManager.sendMessage(message.conversationPeerId, payload) ||
            sendViaMailbox(message.conversationPeerId, messageId, message.content, message.timestamp)
        database.messageDao().updateDeliveryStatus(messageId, if (ok) "SENT" else "FAILED")
        return ok
    }

    fun markConversationAsRead(peerId: String) {
        scope.launch(Dispatchers.IO) {
            database.messageDao().markConversationAsRead(peerId)
        }
    }
}
