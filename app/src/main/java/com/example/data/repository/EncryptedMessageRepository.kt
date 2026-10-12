package com.example.data.repository

import com.example.data.EncryptedMessageDao
import com.example.data.EncryptedMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing encrypted message history stored locally on the device.
 * Abstracts DAO interactions and provides a clean reactive interface for UI and background sync.
 */
interface EncryptedMessageRepository {

    /**
     * Observes the ordered encrypted message thread for a specific conversation peer.
     */
    fun getMessagesForConversation(conversationPeerId: String): Flow<List<EncryptedMessageEntity>>

    /**
     * Observes recent encrypted messages across all conversations.
     */
    fun getRecentMessages(limit: Int = 100): Flow<List<EncryptedMessageEntity>>

    /**
     * Retrieves a single encrypted message record by its unique ID.
     */
    suspend fun getMessageById(id: String): EncryptedMessageEntity?

    /**
     * Persists a single encrypted message record.
     */
    suspend fun saveMessage(message: EncryptedMessageEntity): Long

    /**
     * Persists multiple encrypted message records in a batch operation.
     */
    suspend fun saveMessages(messages: List<EncryptedMessageEntity>)

    /**
     * Updates the delivery state of an existing encrypted message.
     */
    suspend fun updateDeliveryStatus(id: String, status: String)

    /**
     * Deletes a specific encrypted message by its unique ID.
     */
    suspend fun deleteMessage(id: String): Boolean

    /**
     * Deletes all encrypted message records for a specific conversation peer.
     */
    suspend fun deleteConversation(conversationPeerId: String): Boolean

    /**
     * Counts the total number of encrypted messages stored for a conversation.
     */
    suspend fun getMessageCount(conversationPeerId: String): Int

    /**
     * Clears all encrypted message records from the local device storage.
     */
    suspend fun clearAll(): Int
}

/**
 * Production implementation of [EncryptedMessageRepository] backed by Room Database [EncryptedMessageDao].
 */
class LocalEncryptedMessageRepository(
    private val encryptedMessageDao: EncryptedMessageDao
) : EncryptedMessageRepository {

    override fun getMessagesForConversation(conversationPeerId: String): Flow<List<EncryptedMessageEntity>> {
        return encryptedMessageDao.getMessagesForConversationFlow(conversationPeerId)
    }

    override fun getRecentMessages(limit: Int): Flow<List<EncryptedMessageEntity>> {
        return encryptedMessageDao.getRecentMessagesFlow(limit)
    }

    override suspend fun getMessageById(id: String): EncryptedMessageEntity? {
        return encryptedMessageDao.getMessageById(id)
    }

    override suspend fun saveMessage(message: EncryptedMessageEntity): Long {
        return encryptedMessageDao.insertMessage(message)
    }

    override suspend fun saveMessages(messages: List<EncryptedMessageEntity>) {
        encryptedMessageDao.insertMessages(messages)
    }

    override suspend fun updateDeliveryStatus(id: String, status: String) {
        encryptedMessageDao.updateDeliveryStatus(id, status)
    }

    override suspend fun deleteMessage(id: String): Boolean {
        return encryptedMessageDao.deleteMessageById(id) > 0
    }

    override suspend fun deleteConversation(conversationPeerId: String): Boolean {
        return encryptedMessageDao.deleteConversation(conversationPeerId) > 0
    }

    override suspend fun getMessageCount(conversationPeerId: String): Int {
        return encryptedMessageDao.getMessageCount(conversationPeerId)
    }

    override suspend fun clearAll(): Int {
        return encryptedMessageDao.deleteAll()
    }
}
