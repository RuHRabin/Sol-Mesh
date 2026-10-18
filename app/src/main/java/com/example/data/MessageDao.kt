package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationPeerId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForConversationFlow(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 100")
    fun getRecentMessagesFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationPeerId = :peerId AND isOutgoing = 1 AND deliveryStatus IN ('FAILED', 'SENDING') AND timestamp > :since ORDER BY timestamp ASC")
    suspend fun getUnsentOutgoing(peerId: String, since: Long): List<MessageEntity>

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateDeliveryStatus(id: String, status: String)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationPeerId = :peerId AND isOutgoing = 0")
    suspend fun markConversationAsRead(peerId: String)

    @Query("DELETE FROM messages WHERE conversationPeerId = :peerId")
    suspend fun deleteConversation(peerId: String)
}
