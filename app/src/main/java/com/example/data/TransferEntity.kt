package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfers",
    indices = [
        Index("fileId", unique = true),
        Index("peerId"),
        Index("updatedAt")
    ]
)
data class TransferEntity(
    @PrimaryKey val id: String,
    val fileId: String,
    val peerId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val chunkSize: Int = 65536,
    val chunksTotal: Int,
    val chunksTransferred: Int = 0,
    val status: String = "PENDING", // PENDING, ACCEPTED, TRANSFERRING, PAUSED, COMPLETED, FAILED, CANCELLED
    val localFilePath: String,
    val sha256Hash: String,
    val isOutgoing: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
