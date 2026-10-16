package com.example.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RelayClientTest {

    /** Tiny in-process relay following the protocol in relay/relay_server.py. */
    private class FakeRelay {
        val server = ServerSocket(0)
        private val waiting = HashMap<String, Socket>()

        init {
            thread(isDaemon = true) {
                try {
                    while (true) {
                        val client = server.accept()
                        thread(isDaemon = true) { handle(client) }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun readLine(s: Socket): String {
            val sb = StringBuilder()
            while (true) {
                val b = s.getInputStream().read()
                if (b < 0 || b == '\n'.code) return sb.toString()
                sb.append(b.toChar())
            }
        }

        private fun handle(client: Socket) {
            val parts = readLine(client).trim().split(" ")
            if (parts.size != 2 || parts[0] != "JOIN") {
                client.getOutputStream().write("ERR bad request\n".toByteArray())
                client.close()
                return
            }
            val other = synchronized(waiting) { waiting.remove(parts[1]) ?: run { waiting[parts[1]] = client; null } }
            if (other == null) return
            other.getOutputStream().write("PAIRED I\n".toByteArray())
            client.getOutputStream().write("PAIRED R\n".toByteArray())
            thread(isDaemon = true) { pipe(other, client) }
            pipe(client, other)
        }

        private fun pipe(from: Socket, to: Socket) {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = from.getInputStream().read(buf)
                    if (n < 0) break
                    to.getOutputStream().write(buf, 0, n)
                    to.getOutputStream().flush()
                }
            } catch (_: Exception) {
            } finally {
                try { to.close() } catch (_: Exception) {}
            }
        }
    }

    private fun readN(s: Socket, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = s.getInputStream().read(b, o, n - o)
            check(r >= 0) { "eof" }
            o += r
        }
        return b
    }

    private fun joinAsync(port: Int, code: String, waitMs: Long = 20_000):
        Pair<Thread, AtomicReference<RelayClient.Paired?>> {
        val ref = AtomicReference<RelayClient.Paired?>()
        return thread { ref.set(RelayClient.join("127.0.0.1", port, code, waitMs = waitMs)) } to ref
    }

    @Test
    fun generatedCodesAreValidUniqueAndHaveNoLookAlikes() {
        val codes = (1..200).map { RelayClient.generateCode() }.toSet()
        assertEquals(200, codes.size)
        for (c in codes) {
            assertEquals(true, RelayClient.isValidCode(c))
            assertEquals(false, c.any { it in "01OIL" })
        }
        assertEquals(false, RelayClient.isValidCode("short"))
        assertEquals(false, RelayClient.isValidCode("has space 123"))
    }

    @Test
    fun relayAddressParsing() {
        assertEquals("relay.example.com" to 9000, RelayClient.parseRelayAddress(" relay.example.com "))
        assertEquals("1.2.3.4" to 7000, RelayClient.parseRelayAddress("1.2.3.4:7000"))
        assertEquals("2001:db8::1" to 9000, RelayClient.parseRelayAddress("[2001:db8::1]:9000"))
        assertNull(RelayClient.parseRelayAddress(""))
        assertNull(RelayClient.parseRelayAddress("a b"))
    }

    @Test
    fun twoPhonesPairWithDifferentRolesAndDataFlowsBothWays() {
        val relay = FakeRelay()
        val code = RelayClient.generateCode()
        val (ta, a) = joinAsync(relay.server.localPort, code)
        Thread.sleep(400)
        val (tb, b) = joinAsync(relay.server.localPort, code)
        ta.join()
        tb.join()
        val pa = a.get()!!
        val pb = b.get()!!
        assertEquals(true, pa.initiator)
        assertEquals(false, pb.initiator)

        // bytes sent right behind the PAIRED line must not be swallowed by the client
        pb.socket.getOutputStream().write("right-after-pairing".toByteArray())
        pb.socket.getOutputStream().flush()
        assertEquals("right-after-pairing", String(readN(pa.socket, 19)))
        pa.socket.getOutputStream().write("reply".toByteArray())
        pa.socket.getOutputStream().flush()
        assertEquals("reply", String(readN(pb.socket, 5)))
        pa.socket.close()
        pb.socket.close()
    }

    @Test
    fun waitingPhoneGetsNullWhenNobodyJoins() {
        val relay = FakeRelay()
        val (t, r) = joinAsync(relay.server.localPort, RelayClient.generateCode(), waitMs = 1200)
        t.join()
        assertNull(r.get())
    }

    @Test
    fun badRequestAndUnreachableRelayAreReportedAsIoException() {
        assertThrows(IOException::class.java) {
            RelayClient.join("127.0.0.1", ServerSocket(0).use { it.localPort }, RelayClient.generateCode(), waitMs = 1000, connectMs = 500)
        }
    }
}
