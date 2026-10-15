package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeersFlow(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    suspend fun getAllPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    suspend fun getPeer(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1")
    fun getPeerFlow(peerId: String): Flow<PeerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Update
    suspend fun updatePeer(peer: PeerEntity)

    @Query("UPDATE peers SET connectionState = :state WHERE peerId = :peerId")
    suspend fun updateConnectionState(peerId: String, state: String)

    @Query("UPDATE peers SET isOnline = :isOnline, lastSeen = :lastSeen WHERE peerId = :peerId")
    suspend fun updateOnlineStatus(peerId: String, isOnline: Boolean, lastSeen: Long)

    @Query("UPDATE peers SET isVerified = :isVerified WHERE peerId = :peerId")
    suspend fun updateVerified(peerId: String, isVerified: Boolean)

    @Query("DELETE FROM peers WHERE peerId = :peerId")
    suspend fun deletePeer(peerId: String)
}
