package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val publicKeyHex: String,
    val dhPublicKeyHex: String,
    val addressesJson: String,
    val lastSeen: Long,
    val isOnline: Boolean = false,
    val isDirectReachable: Boolean = false,
    val isVerified: Boolean = false,
    val connectionState: String = "DISCONNECTED"
)
