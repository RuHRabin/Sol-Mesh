package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local encrypted message persistence.
 */
@Dao
interface EncryptedMessageDao {

    @Query("SELECT * FROM encrypted_messages WHERE conversationPeerId = :conversationPeerId ORDER BY timestamp ASC")
    fun getMessagesForConversationFlow(conversationPeerId: String): Flow<List<EncryptedMessageEntity>>

    @Query("SELECT * FROM encrypted_messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessagesFlow(limit: Int = 100): Flow<List<EncryptedMessageEntity>>

    @Query("SELECT * FROM encrypted_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): EncryptedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: EncryptedMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<EncryptedMessageEntity>)

    @Update
    suspend fun updateMessage(message: EncryptedMessageEntity)

    @Query("UPDATE encrypted_messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateDeliveryStatus(id: String, status: String)

    @Query("DELETE FROM encrypted_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String): Int

    @Query("DELETE FROM encrypted_messages WHERE conversationPeerId = :conversationPeerId")
    suspend fun deleteConversation(conversationPeerId: String): Int

    @Query("SELECT COUNT(*) FROM encrypted_messages WHERE conversationPeerId = :conversationPeerId")
    suspend fun getMessageCount(conversationPeerId: String): Int

    @Query("DELETE FROM encrypted_messages")
    suspend fun deleteAll(): Int
}
