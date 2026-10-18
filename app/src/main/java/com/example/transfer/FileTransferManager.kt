package com.example.transfer

import android.content.Context
import android.util.Log
import com.example.crypto.AeadCipher
import com.example.crypto.IdentityManager
import com.example.data.AppDatabase
import com.example.data.TransferEntity
import com.example.p2p.ConnectionManager
import com.example.protocol.FileCommandType
import com.example.protocol.FileEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct Chunked Peer-to-Peer File Transfer Manager.
 * Operates over /p2p-file/1.0.0 stream with zero cloud involvement.
 * Supports streaming I/O (no huge files in RAM), per-chunk encryption,
 * resumption after connection drop, and end-to-end SHA-256 verification.
 */
class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val database: AppDatabase,
    private val connectionManager: ConnectionManager
) {
    companion object {
        private const val TAG = "FileTransferManager"
        const val CHUNK_SIZE = 64 * 1024 // 64 KB
        const val MAX_INCOMING_FILE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private val FILE_ID_REGEX = Regex("^[A-Za-z0-9-]{1,64}$")
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }

    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    private val transferLocks = ConcurrentHashMap<String, Any>()

    private fun sanitizeFileName(raw: String?): String {
        var name = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        name = name.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.')
        if (name.length > 100) name = name.take(100)
        return name.ifEmpty { "received_file_${System.currentTimeMillis()}" }
    }

    private fun getTransfersDir(): File {
        val dir = File(context.filesDir, "nexus_transfers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Initiates sending a local file to a peer.
     */
    fun sendFile(peerId: String, file: File, mimeType: String = "application/octet-stream") {
        if (!file.exists()) return

        scope.launch(Dispatchers.IO) {
            val fileId = UUID.randomUUID().toString()
            val fileSizeBytes = file.length()
            val totalChunks = ((fileSizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)

            // Compute overall file SHA-256 hash
            val sha256 = calculateFileSha256(file)

            val transfer = TransferEntity(
                id = UUID.randomUUID().toString(),
                fileId = fileId,
                peerId = peerId,
                fileName = file.name,
                fileSizeBytes = fileSizeBytes,
                mimeType = mimeType,
                chunkSize = CHUNK_SIZE,
                chunksTotal = totalChunks,
                chunksTransferred = 0,
                status = "PENDING",
                localFilePath = file.absolutePath,
                sha256Hash = sha256,
                isOutgoing = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.transferDao().upsertTransfer(transfer)

            // Send FILE_INIT
            val initEnvelope = FileEnvelope(
                command = FileCommandType.FILE_INIT,
                fileId = fileId,
                senderPeerId = database.peerDao().getPeer(peerId)?.let { "" } ?: "",
                recipientPeerId = peerId,
                fileName = file.name,
                fileSizeBytes = fileSizeBytes,
                chunkSize = CHUNK_SIZE,
                totalChunks = totalChunks,
                sha256Hash = sha256,
                mimeType = mimeType
            )
            connectionManager.sendFileEnvelope(peerId, initEnvelope)
        }
    }

    /**
     * Handles incoming FileEnvelope messages from remote peer.
     */
    fun handleIncomingFileEnvelope(envelope: FileEnvelope) {
        scope.launch(Dispatchers.IO) {
            // Every command except FILE_INIT must refer to an existing transfer with this peer,
            // in the right direction.
            if (envelope.command != FileCommandType.FILE_INIT) {
                val t = database.transferDao().getTransferByFileId(envelope.fileId) ?: return@launch
                if (t.peerId != envelope.senderPeerId) return@launch
                val incomingCommand = envelope.command == FileCommandType.FILE_CHUNK ||
                    envelope.command == FileCommandType.FILE_COMPLETE
                val outgoingCommand = envelope.command == FileCommandType.FILE_ACCEPT ||
                    envelope.command == FileCommandType.FILE_ACK ||
                    envelope.command == FileCommandType.FILE_RESUME
                if (incomingCommand && t.isOutgoing) return@launch
                if (outgoingCommand && !t.isOutgoing) return@launch
            }
            when (envelope.command) {
                FileCommandType.FILE_INIT -> handleFileInit(envelope)
                FileCommandType.FILE_ACCEPT -> handleFileAccept(envelope)
                FileCommandType.FILE_REJECT -> handleFileReject(envelope)
                FileCommandType.FILE_CHUNK -> handleFileChunk(envelope)
                FileCommandType.FILE_ACK -> handleFileAck(envelope)
                FileCommandType.FILE_RESUME -> handleFileResume(envelope)
                FileCommandType.FILE_COMPLETE -> handleFileComplete(envelope)
                FileCommandType.FILE_ERROR -> handleFileError(envelope)
            }
        }
    }

    /** Decides whether files from this peer may be received at all (false until the user accepts them). */
    @Volatile
    var isPeerAllowed: (String) -> Boolean = { true }

    private suspend fun handleFileInit(env: FileEnvelope) {
        if (!isPeerAllowed(env.senderPeerId)) {
            Log.w(TAG, "Refused file offer from a contact that is not accepted")
            sendError(env.senderPeerId, env.fileId, "Not accepted yet")
            return
        }
        // Never trust remote-supplied identifiers or sizes
        if (!FILE_ID_REGEX.matches(env.fileId) || env.senderPeerId.isEmpty()) {
            Log.w(TAG, "Rejected FILE_INIT with invalid fileId")
            return
        }
        val known = database.transferDao().getTransferByFileId(env.fileId)
        if (known != null && (known.isOutgoing || known.peerId != env.senderPeerId)) {
            Log.w(TAG, "Rejected FILE_INIT for fileId owned by another transfer")
            return
        }
        val fileName = sanitizeFileName(env.fileName)
        val totalSize = env.fileSizeBytes ?: 0L
        val expectedChunks = ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
        val totalChunks = env.totalChunks ?: expectedChunks
        val sha256 = env.sha256Hash ?: ""
        val mimeType = (env.mimeType ?: "application/octet-stream").take(100)

        val sizeOk = totalSize in 0..MAX_INCOMING_FILE_BYTES &&
            totalChunks == expectedChunks &&
            (env.chunkSize == null || env.chunkSize == CHUNK_SIZE) &&
            (sha256.isEmpty() || SHA256_HEX_REGEX.matches(sha256))
        if (!sizeOk) {
            Log.w(TAG, "Rejected FILE_INIT with invalid size/chunk metadata")
            sendError(env.senderPeerId, env.fileId, "Invalid file metadata")
            return
        }

        val transfersDir = getTransfersDir().canonicalFile
        val destFile = File(transfersDir, "${env.fileId}_$fileName").canonicalFile
        if (destFile.parentFile != transfersDir) {
            Log.w(TAG, "Rejected FILE_INIT with path escaping transfers directory")
            return
        }

        // Check if partially downloaded
        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        val resumeFromChunk = (existingBytes / CHUNK_SIZE).toInt().coerceAtMost(totalChunks)

        val transfer = TransferEntity(
            id = UUID.randomUUID().toString(),
            fileId = env.fileId,
            peerId = env.senderPeerId,
            fileName = fileName,
            fileSizeBytes = totalSize,
            mimeType = mimeType,
            chunkSize = CHUNK_SIZE,
            chunksTotal = totalChunks,
            chunksTransferred = resumeFromChunk,
            status = "TRANSFERRING",
            localFilePath = destFile.absolutePath,
            sha256Hash = sha256,
            isOutgoing = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.transferDao().upsertTransfer(transfer)

        // Automatically accept and request starting/resuming from chunk
        val acceptEnv = FileEnvelope(
            command = FileCommandType.FILE_ACCEPT,
            fileId = env.fileId,
            senderPeerId = env.recipientPeerId,
            recipientPeerId = env.senderPeerId,
            chunkIndex = resumeFromChunk
        )
        connectionManager.sendFileEnvelope(env.senderPeerId, acceptEnv)
    }

    private suspend fun handleFileAccept(env: FileEnvelope) {
        val transfer = database.transferDao().getTransferByFileId(env.fileId) ?: return
        val startChunk = (env.chunkIndex ?: 0).coerceIn(0, transfer.chunksTotal)

        database.transferDao().updateStatus(env.fileId, "TRANSFERRING", System.currentTimeMillis())

        // Launch chunk streaming job
        val file = File(transfer.localFilePath)
        if (!file.exists()) {
            sendError(transfer.peerId, env.fileId, "Local file no longer exists")
            return
        }

        activeSendJobs[env.fileId]?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            streamFileChunks(transfer.peerId, env.fileId, file, startChunk, transfer.chunksTotal, transfer.sha256Hash)
        }
        activeSendJobs[env.fileId] = job
    }

    private suspend fun streamFileChunks(
        peerId: String,
        fileId: String,
        file: File,
        startChunk: Int,
        totalChunks: Int,
        overallSha256: String
    ) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(CHUNK_SIZE)
                for (chunkIdx in startChunk until totalChunks) {
                    if (!scope.isActive || !connectionManager.isConnected(peerId)) {
                        database.transferDao().updateStatus(fileId, "PAUSED", System.currentTimeMillis())
                        return
                    }

                    raf.seek(chunkIdx.toLong() * CHUNK_SIZE)
                    val bytesRead = raf.read(buffer)
                    if (bytesRead <= 0) break

                    val chunkBytes = if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead)
                    val chunkSha = calculateBytesSha256(chunkBytes)

                    val chunkEnvelope = FileEnvelope(
                        command = FileCommandType.FILE_CHUNK,
                        fileId = fileId,
                        senderPeerId = "",
                        recipientPeerId = peerId,
                        chunkIndex = chunkIdx,
                        chunkSha256 = chunkSha,
                        encryptedDataHex = IdentityManager.bytesToHex(chunkBytes)
                    )

                    val sent = connectionManager.sendFileEnvelope(peerId, chunkEnvelope)
                    if (!sent) {
                        database.transferDao().updateStatus(fileId, "PAUSED", System.currentTimeMillis())
                        return
                    }

                    database.transferDao().updateProgress(
                        fileId = fileId,
                        chunksTransferred = chunkIdx + 1,
                        status = "TRANSFERRING",
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }

            // All chunks sent, send FILE_COMPLETE
            val completeEnv = FileEnvelope(
                command = FileCommandType.FILE_COMPLETE,
                fileId = fileId,
                senderPeerId = "",
                recipientPeerId = peerId,
                sha256Hash = overallSha256
            )
            connectionManager.sendFileEnvelope(peerId, completeEnv)
            database.transferDao().updateStatus(fileId, "COMPLETED", System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming file chunks: ${e.message}", e)
            database.transferDao().updateStatus(fileId, "FAILED", System.currentTimeMillis())
        }
    }

    private suspend fun handleFileChunk(env: FileEnvelope) {
        val transfer = database.transferDao().getTransferByFileId(env.fileId) ?: return
        val chunkIdx = env.chunkIndex ?: return
        if (chunkIdx < 0 || chunkIdx >= transfer.chunksTotal) return
        if (transfer.status != "TRANSFERRING") return
        val hex = env.encryptedDataHex ?: return
        if (hex.length % 2 != 0 || hex.length > CHUNK_SIZE * 2) return
        val chunkData = IdentityManager.hexToBytes(hex)
        // All chunks except the last must be full-size; the last one must match the remainder
        val expectedLen = if (chunkIdx == transfer.chunksTotal - 1) {
            (transfer.fileSizeBytes - chunkIdx.toLong() * CHUNK_SIZE).toInt()
        } else CHUNK_SIZE
        if (chunkData.size != expectedLen) return

        // Verify chunk integrity (hash is mandatory)
        val calculatedSha = calculateBytesSha256(chunkData)
        if (env.chunkSha256 == null || !env.chunkSha256.equals(calculatedSha, ignoreCase = true)) {
            Log.e(TAG, "Chunk $chunkIdx SHA-256 mismatch!")
            return
        }

        val destFile = File(transfer.localFilePath)
        val lock = transferLocks.getOrPut(env.fileId) { Any() }

        synchronized(lock) {
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(chunkIdx.toLong() * transfer.chunkSize)
                raf.write(chunkData)
            }
        }

        val transferred = chunkIdx + 1
        database.transferDao().updateProgress(
            fileId = env.fileId,
            chunksTransferred = transferred,
            status = if (transferred >= transfer.chunksTotal) "COMPLETED" else "TRANSFERRING",
            updatedAt = System.currentTimeMillis()
        )

        // Send ACK
        val ackEnv = FileEnvelope(
            command = FileCommandType.FILE_ACK,
            fileId = env.fileId,
            senderPeerId = env.recipientPeerId,
            recipientPeerId = env.senderPeerId,
            chunkIndex = chunkIdx,
            nextExpectedChunk = chunkIdx + 1
        )
        connectionManager.sendFileEnvelope(env.senderPeerId, ackEnv)
    }

    private suspend fun handleFileAck(env: FileEnvelope) {
        val next = env.nextExpectedChunk ?: return
        database.transferDao().updateProgress(
            fileId = env.fileId,
            chunksTransferred = next,
            status = "TRANSFERRING",
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun handleFileResume(env: FileEnvelope) {
        handleFileAccept(env)
    }

    private suspend fun handleFileComplete(env: FileEnvelope) {
        val transfer = database.transferDao().getTransferByFileId(env.fileId) ?: return
        val file = File(transfer.localFilePath)
        if (!file.exists()) {
            database.transferDao().updateStatus(env.fileId, "FAILED", System.currentTimeMillis())
            return
        }

        val finalSha = calculateFileSha256(file)
        if (transfer.sha256Hash.isNotEmpty() && transfer.sha256Hash != finalSha) {
            Log.e(TAG, "Final file verification failed: expected ${transfer.sha256Hash} got $finalSha")
            database.transferDao().updateStatus(env.fileId, "FAILED", System.currentTimeMillis())
        } else {
            database.transferDao().updateProgress(
                fileId = env.fileId,
                chunksTransferred = transfer.chunksTotal,
                status = "COMPLETED",
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private suspend fun handleFileReject(env: FileEnvelope) {
        database.transferDao().updateStatus(env.fileId, "CANCELLED", System.currentTimeMillis())
    }

    private suspend fun handleFileError(env: FileEnvelope) {
        database.transferDao().updateStatus(env.fileId, "FAILED", System.currentTimeMillis())
    }

    fun resumeTransfer(fileId: String) {
        scope.launch(Dispatchers.IO) {
            val transfer = database.transferDao().getTransferByFileId(fileId) ?: return@launch
            if (!connectionManager.isConnected(transfer.peerId)) return@launch

            if (transfer.isOutgoing) {
                val resumeEnv = FileEnvelope(
                    command = FileCommandType.FILE_RESUME,
                    fileId = fileId,
                    senderPeerId = "",
                    recipientPeerId = transfer.peerId,
                    chunkIndex = transfer.chunksTransferred
                )
                connectionManager.sendFileEnvelope(transfer.peerId, resumeEnv)
            } else {
                val resumeEnv = FileEnvelope(
                    command = FileCommandType.FILE_RESUME,
                    fileId = fileId,
                    senderPeerId = "",
                    recipientPeerId = transfer.peerId,
                    chunkIndex = transfer.chunksTransferred
                )
                connectionManager.sendFileEnvelope(transfer.peerId, resumeEnv)
            }
        }
    }

    fun cancelTransfer(fileId: String) {
        scope.launch(Dispatchers.IO) {
            activeSendJobs.remove(fileId)?.cancel()
            val transfer = database.transferDao().getTransferByFileId(fileId)
            if (transfer != null) {
                database.transferDao().updateStatus(fileId, "CANCELLED", System.currentTimeMillis())
                val rejectEnv = FileEnvelope(
                    command = FileCommandType.FILE_REJECT,
                    fileId = fileId,
                    senderPeerId = "",
                    recipientPeerId = transfer.peerId,
                    errorMessage = "Cancelled by user"
                )
                connectionManager.sendFileEnvelope(transfer.peerId, rejectEnv)
            }
        }
    }

    private fun sendError(peerId: String, fileId: String, msg: String) {
        val err = FileEnvelope(
            command = FileCommandType.FILE_ERROR,
            fileId = fileId,
            senderPeerId = "",
            recipientPeerId = peerId,
            errorMessage = msg
        )
        connectionManager.sendFileEnvelope(peerId, err)
    }

    fun calculateFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16384)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return IdentityManager.bytesToHex(md.digest())
    }

    fun calculateBytesSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return IdentityManager.bytesToHex(md.digest(bytes))
    }
}
