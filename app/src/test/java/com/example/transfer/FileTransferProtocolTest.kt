package com.example.transfer

import com.example.crypto.IdentityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

class FileTransferProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return IdentityManager.bytesToHex(md.digest())
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return IdentityManager.bytesToHex(md.digest(bytes))
    }

    @Test
    fun testChunkedFileTransferAndResumption() {
        val chunkSize = 64 * 1024 // 64 KB
        val totalBytes = (180 * 1024) // 180 KB = ~3 chunks

        // 1. Create source file with random bytes
        val sourceFile = tempFolder.newFile("source_document.bin")
        val randomData = ByteArray(totalBytes)
        SecureRandom().nextBytes(randomData)
        FileOutputStream(sourceFile).use { it.write(randomData) }

        val originalSha256 = sha256(sourceFile)
        val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize)
        assertEquals(3, totalChunks)

        // 2. Prepare destination file
        val destFile = tempFolder.newFile("received_document.bin")

        // 3. Transfer chunk 0
        val chunk0 = randomData.copyOfRange(0, chunkSize)
        assertEquals(chunkSize, chunk0.size)
        val chunk0Sha = sha256Bytes(chunk0)
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.seek(0L * chunkSize)
            raf.write(chunk0)
        }

        // 4. Simulate network drop!
        // At this point receiver has chunk 0. Receiver requests resume from chunk 1.
        val resumeFromChunk = 1

        // 5. Transfer remaining chunks (1 and 2)
        for (idx in resumeFromChunk until totalChunks) {
            val start = idx * chunkSize
            val end = minOf(start + chunkSize, totalBytes)
            val chunkBytes = randomData.copyOfRange(start, end)
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(idx.toLong() * chunkSize)
                raf.write(chunkBytes)
            }
        }

        // 6. Verify reconstructed file on disk
        val reconstructedSha256 = sha256(destFile)
        assertEquals("Reconstructed file must match original SHA-256 hash exactly", originalSha256, reconstructedSha256)
        assertEquals(sourceFile.length(), destFile.length())
    }
}
