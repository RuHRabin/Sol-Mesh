package com.example.p2p

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** The phone's identity as the mailbox server needs it: public key plus a way to sign. */
class MailboxIdentity(val publicKey: ByteArray, val sign: (ByteArray) -> ByteArray)

/**
 * Client for the offline-message mailbox on the sol-mesh server (see relay/relay_server.py).
 *
 * Messages are already sealed end to end (see SealedBox) before they get here; this class only moves
 * the sealed bytes. Every request is signed with the phone's Ed25519 key, so only the owner of a mailbox
 * can open it and only a real key holder can leave a message.
 *
 * Pure JVM on purpose (no Android imports) so it can be tested on a laptop against the real server.
 */
object MailboxClient {
    class PutResult(val ok: Boolean, val error: String?)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return try {
            ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** The mailbox id of a person: sha256 of their Ed25519 public key, in hex. */
    fun mailboxIdOf(publicKey: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(publicKey))

    private fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    internal fun putLine(identity: MailboxIdentity, toMailbox: String, blob: ByteArray, tsMillis: Long): String {
        val signed = "SolMeshPUT1|$toMailbox|$tsMillis|${sha256Hex(blob)}".toByteArray(Charsets.US_ASCII)
        val signature = identity.sign(signed)
        return "PUT $toMailbox ${hex(identity.publicKey)} $tsMillis ${hex(signature)} ${hex(blob)}\n"
    }

    internal fun subLine(identity: MailboxIdentity, tsMillis: Long): String {
        val signed = "SolMeshSUB1|${hex(identity.publicKey)}|$tsMillis".toByteArray(Charsets.US_ASCII)
        return "SUB ${hex(identity.publicKey)} $tsMillis ${hex(identity.sign(signed))}\n"
    }

    /** Leaves a sealed message in [toMailbox]. Never throws: problems come back in the result. */
    fun put(
        host: String,
        port: Int,
        identity: MailboxIdentity,
        toMailbox: String,
        blob: ByteArray,
        connectMs: Int = 8000,
        readMs: Int = 10_000,
        nowMillis: Long = System.currentTimeMillis()
    ): PutResult {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), connectMs)
            socket.soTimeout = readMs
            socket.getOutputStream().apply {
                write(putLine(identity, toMailbox, blob, nowMillis).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val answer = LineReader(socket).readLineBlocking() ?: return PutResult(false, "No answer from the server")
            when {
                answer.startsWith("OK") -> PutResult(true, null)
                answer.startsWith("ERR") -> PutResult(false, answer.removePrefix("ERR").trim())
                else -> PutResult(false, "Unexpected answer from the server")
            }
        } catch (e: Exception) {
            PutResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Reads lines from a socket without ever losing a half-received line when a read times out.
     * A byte buffer is used so long lines (a message is about 16 KB of hex) do not cost one system call per byte.
     */
    internal class LineReader(socket: Socket, private val maxLine: Int = 40_000) {
        private val input = BufferedInputStream(socket.getInputStream(), 8192)
        private val pending = ByteArrayOutputStream()

        /** The next line, or null if the read timed out before a whole line arrived (partial data is kept). */
        fun readLine(): String? {
            while (true) {
                val b = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    return null
                }
                if (b < 0) throw IOException("Connection closed")
                if (b == '\n'.code) {
                    val line = pending.toString(Charsets.US_ASCII.name())
                    pending.reset()
                    return line.trim()
                }
                pending.write(b)
                if (pending.size() > maxLine) throw IOException("Line too long")
            }
        }

        fun readLineBlocking(): String? = try { readLine() } catch (_: IOException) { null }
    }
}

/**
 * Keeps one live connection to the server and hands every waiting and every new message to [onMessage].
 * The message is deleted from the server only after [onMessage] returns true (the app has stored it).
 * Reconnects by itself with a growing pause.
 */
class MailboxSubscriber(
    private val host: String,
    private val port: Int,
    private val identity: MailboxIdentity,
    private val onMessage: (id: Long, createdSeconds: Long, blob: ByteArray) -> Boolean,
    private val onStatus: (String) -> Unit,
    private val pingEveryMs: Int = 30_000
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null
    private var lastTs = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "mailbox-subscriber").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
    }

    private fun nextTimestamp(): Long {
        lastTs = maxOf(System.currentTimeMillis(), lastTs + 1)   // never the same twice
        return lastTs
    }

    private fun runLoop() {
        var pause = 2000L
        while (running) {
            onStatus("Connecting...")
            val reachedServer = try {
                session()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                if (running) onStatus("Server unreachable. Retrying...")
                false
            }
            if (!running) return
            pause = if (reachedServer) 2000L else minOf(pause * 2, 60_000L)
            try {
                Thread.sleep(pause + (Math.random() * 500).toLong())
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** One connection. Returns true if the server accepted us (so the retry pause can reset). */
    private fun session(): Boolean {
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 8000)
            s.tcpNoDelay = true
            s.soTimeout = 2000   // short while waiting for the server's first answer
            s.getOutputStream().apply {
                write(MailboxClient.subLine(identity, nextTimestamp()).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val reader = MailboxClient.LineReader(s)
            val first = readWithDeadline(reader, 10_000) ?: throw IOException("No answer")
            if (!first.startsWith("OK")) {
                onStatus(describeRefusal(first))
                return false
            }
            onStatus("Connected")
            s.soTimeout = pingEveryMs

            var lastHeard = System.currentTimeMillis()
            val out = s.getOutputStream()
            while (running) {
                val line = reader.readLine()
                if (line == null) {
                    // quiet for pingEveryMs: prove the connection is alive
                    if (System.currentTimeMillis() - lastHeard > pingEveryMs * 3L) throw IOException("Connection went silent")
                    out.write("PING\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    continue
                }
                lastHeard = System.currentTimeMillis()
                if (line.startsWith("MSG ")) {
                    val parts = line.split(" ")
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    val created = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val blob = parts.getOrNull(3)?.let { MailboxClient.unhex(it) }
                    if (id != null && blob != null) {
                        if (onMessage(id, created, blob)) {
                            out.write("ACK $id\n".toByteArray(Charsets.US_ASCII))
                            out.flush()
                        }
                    }
                } else if (line.startsWith("ERR")) {
                    onStatus(describeRefusal(line))
                    return true
                }
                // "PONG" and anything unknown are ignored
            }
            return true
        } finally {
            try { s.close() } catch (_: Exception) {}
            if (socket === s) socket = null
        }
    }

    private fun readWithDeadline(reader: MailboxClient.LineReader, totalMs: Long): String? {
        val deadline = System.currentTimeMillis() + totalMs
        while (running && System.currentTimeMillis() < deadline) {
            reader.readLine()?.let { return it }
        }
        return null
    }

    private fun describeRefusal(line: String): String = when {
        line.contains("clock") -> "Your phone's clock is wrong (more than 5 minutes off). Fix the time and offline messages will start."
        line.contains("bad request") -> "This server does not support offline messages. Update it from the relay folder."
        line.contains("replay") -> "Server refused a repeated request. Retrying..."
        else -> "Server refused: " + line.removePrefix("ERR").trim()
    }
}
