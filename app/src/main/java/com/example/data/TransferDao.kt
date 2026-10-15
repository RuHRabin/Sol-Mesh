package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY updatedAt DESC")
    fun getAllTransfersFlow(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE fileId = :fileId LIMIT 1")
    suspend fun getTransferByFileId(fileId: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE fileId = :fileId LIMIT 1")
    fun getTransferFlow(fileId: String): Flow<TransferEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(transfer: TransferEntity)

    @Update
    suspend fun updateTransfer(transfer: TransferEntity)

    @Query("UPDATE transfers SET chunksTransferred = :chunksTransferred, updatedAt = :updatedAt, status = :status WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, chunksTransferred: Int, status: String, updatedAt: Long)

    @Query("UPDATE transfers SET status = :status, updatedAt = :updatedAt WHERE fileId = :fileId")
    suspend fun updateStatus(fileId: String, status: String, updatedAt: Long)

    @Query("DELETE FROM transfers WHERE fileId = :fileId")
    suspend fun deleteTransfer(fileId: String)
}
