package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index("conversationPeerId"),
        Index("timestamp")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationPeerId: String,
    val senderPeerId: String,
    val recipientPeerId: String,
    val content: String,
    val timestamp: Long,
    val deliveryStatus: String = "SENT", // SENDING, SENT, DELIVERED, READ, FAILED
    val isOutgoing: Boolean,
    val isRead: Boolean = true
)
