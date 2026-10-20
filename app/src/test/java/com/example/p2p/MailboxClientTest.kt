package com.example.p2p

import com.example.crypto.Curve25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MailboxClientTest {

    private class Person {
        private val kp = Curve25519.ed25519GenerateKeyPair()
        val edPriv: ByteArray = kp.first
        val edPub: ByteArray = kp.second
        val identity = MailboxIdentity(edPub) { m -> Curve25519.ed25519Sign(edPriv, m) }
    }

    @Test
    fun mailboxIdIsTheSha256OfThePublicKey() {
        assertEquals(
            "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
            MailboxClient.mailboxIdOf(ByteArray(32))
        )
    }

    @Test
    fun hexHelpersRoundTripAndRejectGarbage() {
        val bytes = byteArrayOf(0, 1, 0x7f, -1, -128)
        assertTrue(bytes.contentEquals(MailboxClient.unhex(MailboxClient.hex(bytes))!!))
        assertNull(MailboxClient.unhex("abc"))
        assertNull(MailboxClient.unhex("zz"))
    }

    @Test
    fun putRequestCarriesAValidSignatureOverTheDocumentedBytes() {
        val alice = Person()
        val blob = byteArrayOf(1, 2, 3, 4)
        val to = "ab".repeat(32)
        val parts = MailboxClient.putLine(alice.identity, to, blob, 1_700_000_000_123L).trim().split(" ")
        assertEquals(listOf("PUT", to), parts.take(2))
        assertEquals(MailboxClient.hex(alice.edPub), parts[2])
        assertEquals("1700000000123", parts[3])
        assertEquals(MailboxClient.hex(blob), parts[5])
        val digest = MailboxClient.hex(java.security.MessageDigest.getInstance("SHA-256").digest(blob))
        val signed = "SolMeshPUT1|$to|1700000000123|$digest".toByteArray()
        assertTrue(Curve25519.ed25519Verify(alice.edPub, signed, MailboxClient.unhex(parts[4])!!))
        // a different blob does not match this signature
        assertFalse(Curve25519.ed25519Verify(alice.edPub, "SolMeshPUT1|$to|1700000000123|00".toByteArray(), MailboxClient.unhex(parts[4])!!))
    }

    @Test
    fun subscribeRequestCarriesAValidSignature() {
        val bob = Person()
        val parts = MailboxClient.subLine(bob.identity, 42L).trim().split(" ")
        assertEquals("SUB", parts[0])
        val signed = "SolMeshSUB1|${parts[1]}|42".toByteArray()
        assertTrue(Curve25519.ed25519Verify(bob.edPub, signed, MailboxClient.unhex(parts[3])!!))
    }

    /** A tiny in-process server speaking the documented protocol, to test the subscriber loop. */
    private class FakeServer(private val waiting: List<ByteArray>) {
        val server = ServerSocket(0)
        val acked = CopyOnWriteArrayList<Long>()
        val rejected = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val client = server.accept()
                        thread(isDaemon = true) { serve(client) }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun readLine(s: Socket): String? {
            val sb = StringBuilder()
            while (true) {
                val b = s.getInputStream().read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString()
                sb.append(b.toChar())
            }
        }

        private fun serve(client: Socket) {
            try {
                val first = readLine(client)?.split(" ") ?: return
                if (first.size != 4 || first[0] != "SUB") {
                    rejected.add("bad request")
                    return
                }
                val pub = MailboxClient.unhex(first[1])!!
                val sig = MailboxClient.unhex(first[3])!!
                if (!Curve25519.ed25519Verify(pub, "SolMeshSUB1|${first[1]}|${first[2]}".toByteArray(), sig)) {
                    client.getOutputStream().write("ERR bad signature\n".toByteArray())
                    rejected.add("bad signature")
                    return
                }
                val out = client.getOutputStream()
                out.write("OK\n".toByteArray())
                waiting.forEachIndexed { i, blob ->
                    out.write("MSG ${i + 1} 1700000000 ${MailboxClient.hex(blob)}\n".toByteArray())
                }
                out.flush()
                while (true) {
                    val line = readLine(client) ?: break
                    if (line.startsWith("ACK ")) acked.add(line.removePrefix("ACK ").toLong())
                    if (line == "PING") {
                        out.write("PONG\n".toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun waitUntil(ms: Long = 5000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun subscriberDeliversWaitingMessagesAndAcknowledgesOnlyWhatWasStored() {
        val bob = Person()
        val fake = FakeServer(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        val seen = CopyOnWriteArrayList<Byte>()
        val status = CopyOnWriteArrayList<String>()
        val subscriber = MailboxSubscriber(
            "127.0.0.1", fake.server.localPort, bob.identity,
            onMessage = { _, _, blob ->
                seen.add(blob[0])
                blob[0] != 2.toByte() // pretend storing message 2 failed
            },
            onStatus = { status.add(it) }
        )
        subscriber.start()
        waitUntil { seen.size == 3 && fake.acked.size == 2 }
        subscriber.stop()
        assertEquals(listOf<Byte>(1, 2, 3), seen.toList())
        assertEquals(listOf(1L, 3L), fake.acked.toList()) // message 2 is kept on the server
        assertTrue(status.contains("Connected"))
    }

    @Test
    fun subscriberReportsARefusedRequestInPlainWords() {
        val bob = Person()
        val reject = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                while (true) {
                    val c = reject.accept()
                    c.getInputStream().read()
                    c.getOutputStream().write("ERR clock\n".toByteArray())
                    c.close()
                }
            } catch (_: Exception) {
            }
        }
        val status = CopyOnWriteArrayList<String>()
        val subscriber = MailboxSubscriber("127.0.0.1", reject.localPort, bob.identity, { _, _, _ -> true }, { status.add(it) })
        subscriber.start()
        waitUntil { status.any { it.contains("clock") } }
        subscriber.stop()
        assertTrue(status.any { it.contains("Your phone's clock is wrong") })
    }

    @Test
    fun putToAnUnreachableServerReturnsAnErrorInsteadOfThrowing() {
        val alice = Person()
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = MailboxClient.put("127.0.0.1", closedPort, alice.identity, "ab".repeat(32), byteArrayOf(1), connectMs = 500)
        assertFalse(result.ok)
    }
}
