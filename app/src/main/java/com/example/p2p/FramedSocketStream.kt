package com.example.p2p

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Multiplexed binary stream framer over raw TCP/QUIC socket.
 * Format:
 * [4-byte Big-Endian Length][1-byte StreamID][Payload bytes]
 */
class FramedSocketStream(private val socket: Socket) {
    companion object {
        const val STREAM_HANDSHAKE: Byte = 0x01
        const val STREAM_CHAT: Byte = 0x02
        const val STREAM_FILE: Byte = 0x03
        const val STREAM_DHT: Byte = 0x04
        const val STREAM_PING: Byte = 0x05

        const val MAX_FRAME_SIZE = 10 * 1024 * 1024 // 10MB sanity limit
    }

    private val inputStream = DataInputStream(socket.getInputStream())
    private val outputStream = DataOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    data class Frame(val streamId: Byte, val payload: ByteArray)

    fun sendFrame(streamId: Byte, payload: ByteArray) {
        synchronized(writeLock) {
            outputStream.writeInt(payload.size)
            outputStream.writeByte(streamId.toInt())
            outputStream.write(payload)
            outputStream.flush()
        }
    }

    fun readFrame(): Frame {
        val length = inputStream.readInt()
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw IllegalArgumentException("Frame length $length exceeds sanity bound $MAX_FRAME_SIZE")
        }
        val streamId = inputStream.readByte()
        val payload = ByteArray(length)
        inputStream.readFully(payload)
        return Frame(streamId, payload)
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed
}
