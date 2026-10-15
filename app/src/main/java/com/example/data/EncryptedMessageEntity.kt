package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for storing encrypted message history locally on the device.
 * Protects zero-trace communication by persisting the AES-256-GCM / Double Ratchet
 * ciphertext, nonce, sequence numbers, and ratchet key metadata without plaintext exposure.
 */
@Entity(
    tableName = "encrypted_messages",
    indices = [
        Index(value = ["conversationPeerId"]),
        Index(value = ["timestamp"]),
        Index(value = ["senderPeerId"]),
        Index(value = ["recipientPeerId"])
    ]
)
data class EncryptedMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversationPeerId")
    val conversationPeerId: String,

    @ColumnInfo(name = "senderPeerId")
    val senderPeerId: String,

    @ColumnInfo(name = "recipientPeerId")
    val recipientPeerId: String,

    @ColumnInfo(name = "ciphertextHex")
    val ciphertextHex: String,

    @ColumnInfo(name = "nonceHex")
    val nonceHex: String,

    @ColumnInfo(name = "authTagHex")
    val authTagHex: String? = null,

    @ColumnInfo(name = "ratchetKeyHex")
    val ratchetKeyHex: String? = null,

    @ColumnInfo(name = "sequenceNumber")
    val sequenceNumber: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "isOutgoing")
    val isOutgoing: Boolean,

    @ColumnInfo(name = "deliveryStatus")
    val deliveryStatus: String = "SENT", // PENDING, SENT, RECEIVED, FAILED

    @ColumnInfo(name = "keyFingerprint")
    val keyFingerprint: String? = null
)
