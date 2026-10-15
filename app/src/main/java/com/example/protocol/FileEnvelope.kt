package com.example.protocol

import org.json.JSONObject

enum class FileCommandType {
    FILE_INIT,
    FILE_ACCEPT,
    FILE_REJECT,
    FILE_CHUNK,
    FILE_ACK,
    FILE_RESUME,
    FILE_COMPLETE,
    FILE_ERROR
}

/**
 * Message envelope for direct chunked file transfer over /p2p-file/1.0.0 stream.
 * Chunks are streaming, authenticated with SHA-256 and encrypted with session keys.
 */
data class FileEnvelope(
    val command: FileCommandType,
    val fileId: String,
    val senderPeerId: String,
    val recipientPeerId: String,
    // FILE_INIT fields
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val chunkSize: Int? = null,
    val totalChunks: Int? = null,
    val sha256Hash: String? = null,
    val mimeType: String? = null,
    // FILE_CHUNK & FILE_ACK & FILE_RESUME fields
    val chunkIndex: Int? = null,
    val chunkSha256: String? = null,
    val nonceHex: String? = null,
    val encryptedDataHex: String? = null,
    val nextExpectedChunk: Int? = null,
    // FILE_ERROR & REJECT
    val errorMessage: String? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("command", command.name)
        json.put("fileId", fileId)
        json.put("senderPeerId", senderPeerId)
        json.put("recipientPeerId", recipientPeerId)
        fileName?.let { json.put("fileName", it) }
        fileSizeBytes?.let { json.put("fileSizeBytes", it) }
        chunkSize?.let { json.put("chunkSize", it) }
        totalChunks?.let { json.put("totalChunks", it) }
        sha256Hash?.let { json.put("sha256Hash", it) }
        mimeType?.let { json.put("mimeType", it) }
        chunkIndex?.let { json.put("chunkIndex", it) }
        chunkSha256?.let { json.put("chunkSha256", it) }
        nonceHex?.let { json.put("nonceHex", it) }
        encryptedDataHex?.let { json.put("encryptedDataHex", it) }
        nextExpectedChunk?.let { json.put("nextExpectedChunk", it) }
        errorMessage?.let { json.put("errorMessage", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                FileEnvelope(
                    command = FileCommandType.valueOf(json.getString("command")),
                    fileId = json.getString("fileId"),
                    senderPeerId = json.getString("senderPeerId"),
                    recipientPeerId = json.getString("recipientPeerId"),
                    fileName = if (json.has("fileName")) json.getString("fileName") else null,
                    fileSizeBytes = if (json.has("fileSizeBytes")) json.getLong("fileSizeBytes") else null,
                    chunkSize = if (json.has("chunkSize")) json.getInt("chunkSize") else null,
                    totalChunks = if (json.has("totalChunks")) json.getInt("totalChunks") else null,
                    sha256Hash = if (json.has("sha256Hash")) json.getString("sha256Hash") else null,
                    mimeType = if (json.has("mimeType")) json.getString("mimeType") else null,
                    chunkIndex = if (json.has("chunkIndex")) json.getInt("chunkIndex") else null,
                    chunkSha256 = if (json.has("chunkSha256")) json.getString("chunkSha256") else null,
                    nonceHex = if (json.has("nonceHex")) json.getString("nonceHex") else null,
                    encryptedDataHex = if (json.has("encryptedDataHex")) json.getString("encryptedDataHex") else null,
                    nextExpectedChunk = if (json.has("nextExpectedChunk")) json.getInt("nextExpectedChunk") else null,
                    errorMessage = if (json.has("errorMessage")) json.getString("errorMessage") else null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
