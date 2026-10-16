package com.example.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class NatTraversalTest {

    /** Minimal RFC 5389 STUN server over TCP: answers with the client's address as XOR-MAPPED-ADDRESS. */
    private fun fakeStunServer(xor: Boolean): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                while (true) {
                    val c = server.accept()
                    val header = ByteArray(20)
                    var n = 0
                    while (n < 20) n += c.getInputStream().read(header, n, 20 - n)
                    val remote = c.remoteSocketAddress as InetSocketAddress
                    val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                    val addr = remote.address.address
                    val value = ByteArray(8)
                    value[1] = 1
                    val port = if (xor) remote.port xor 0x2112 else remote.port
                    value[2] = (port shr 8).toByte()
                    value[3] = port.toByte()
                    for (i in 0 until 4) {
                        value[4 + i] = if (xor) (addr[i].toInt() xor cookie[i].toInt()).toByte() else addr[i]
                    }
                    val type = if (xor) 0x0020 else 0x0001
                    // an unknown attribute with odd length first, to exercise 4-byte padding
                    val body = byteArrayOf(0x80.toByte(), 0x22, 0, 3, 1, 2, 3, 0) +
                        byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 8) + value
                    val resp = ByteArray(20)
                    resp[0] = 0x01
                    resp[1] = 0x01
                    resp[2] = (body.size shr 8).toByte()
                    resp[3] = body.size.toByte()
                    System.arraycopy(header, 4, resp, 4, 16) // magic cookie + transaction id
                    c.getOutputStream().write(resp + body)
                    c.getOutputStream().flush()
                    c.close()
                }
            } catch (_: Exception) {
            }
        }
        return server
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun stunXorMappedAddressIsParsed() {
        val stun = fakeStunServer(xor = true)
        val local = freePort()
        val result = NatTraversal.discoverTcpMapping(listOf("127.0.0.1" to stun.localPort), local, 3000)
        assertEquals("127.0.0.1:$local", result)
    }

    @Test
    fun stunPlainMappedAddressIsParsed() {
        val stun = fakeStunServer(xor = false)
        val local = freePort()
        val result = NatTraversal.discoverTcpMapping(listOf("127.0.0.1" to stun.localPort), local, 3000)
        assertEquals("127.0.0.1:$local", result)
    }

    @Test
    fun deadServerIsSkippedAndNullWhenNoneAnswer() {
        val dead = freePort()
        assertNull(NatTraversal.discoverTcpMapping(listOf("127.0.0.1" to dead), freePort(), 500))

        val stun = fakeStunServer(xor = true)
        val local = freePort()
        val result = NatTraversal.discoverTcpMapping(
            listOf("127.0.0.1" to dead, "127.0.0.1" to stun.localPort), local, 1500
        )
        assertEquals("127.0.0.1:$local", result)
    }

    @Test
    fun responseWithWrongTransactionIdIsRejected() {
        val header = NatTraversal.buildBindingRequest(ByteArray(12) { 1 })
        header[0] = 0x01
        header[1] = 0x01 // pretend it is a success response
        assertThrows(IllegalArgumentException::class.java) {
            NatTraversal.parseBindingResponse(header, ByteArray(0), ByteArray(12) { 2 })
        }
    }

    @Test
    fun parseHostPortAcceptsValidAndRejectsInvalid() {
        assertEquals("1.2.3.4" to 5, NatTraversal.parseHostPort(" 1.2.3.4:5 "))
        assertEquals("::1" to 9, NatTraversal.parseHostPort("[::1]:9"))
        assertNull(NatTraversal.parseHostPort(""))
        assertNull(NatTraversal.parseHostPort("1.2.3.4"))
        assertNull(NatTraversal.parseHostPort("1.2.3.4:99999"))
        assertNull(NatTraversal.parseHostPort("::1:9"))
        assertEquals("[::1]:9", NatTraversal.format("::1", 9))
        assertEquals("1.2.3.4:5", NatTraversal.format("1.2.3.4", 5))
    }

    @Test
    fun punchReturnsNullWhenNobodyAnswersAndHonoursCancel() {
        assertNull(NatTraversal.punch("127.0.0.1", freePort(), freePort(), totalMs = 1200, attemptMs = 300))
        assertNull(NatTraversal.punch("127.0.0.1", freePort(), freePort(), totalMs = 20_000, cancelled = { true }))
    }

    @Test
    fun shareCodeRoundTripAndTolerantParsing() {
        val c = NatTraversal.Candidates(v4 = "203.0.113.5:9093", v6 = "[2001:db8::1]:9093")
        assertEquals("[2001:db8::1]:9093,203.0.113.5:9093", c.toShareCode())
        assertEquals(c, NatTraversal.parseShareCode(c.toShareCode()))
        assertEquals(
            NatTraversal.Candidates("1.2.3.4:5", "[::1]:9"),
            NatTraversal.parseShareCode("  [::1]:9 ;\n 1.2.3.4:5 \n garbage ")
        )
        assertEquals(true, NatTraversal.parseShareCode("nothing here").isEmpty())
    }

    @Test
    fun bothPhonesPickTheSameAddressFamilyAndExactlyOneIsInitiator() {
        val a = NatTraversal.Candidates("1.1.1.1:1", "[2001:db8::a]:9093")
        val b = NatTraversal.Candidates("2.2.2.2:2", "[2001:db8::b]:9093")
        assertEquals("[2001:db8::a]:9093" to "[2001:db8::b]:9093", NatTraversal.choosePair(a, b))
        assertEquals("[2001:db8::b]:9093" to "[2001:db8::a]:9093", NatTraversal.choosePair(b, a))

        val onlyV4 = NatTraversal.Candidates("2.2.2.2:2", null)
        assertEquals("1.1.1.1:1" to "2.2.2.2:2", NatTraversal.choosePair(a, onlyV4))
        assertEquals("2.2.2.2:2" to "1.1.1.1:1", NatTraversal.choosePair(onlyV4, a))

        assertNull(NatTraversal.choosePair(NatTraversal.Candidates(null, "[::1]:1"), onlyV4))

        val (mineA, theirsA) = NatTraversal.choosePair(a, b)!!
        assertEquals(true, (mineA < theirsA) != (theirsA < mineA))
    }

    @Test
    fun multiaddressUnderstandsIpv6AndKeepsIpv4Behaviour() {
        assertEquals(Multiaddress("2001:db8::1", 9091), Multiaddress.fromString("2001:db8::1"))
        assertEquals(Multiaddress("2001:db8::1", 4000), Multiaddress.fromString("[2001:db8::1]:4000"))
        assertEquals(Multiaddress("2001:db8::1", 4000), Multiaddress.fromString("/ip6/2001:db8::1/tcp/4000"))
        assertEquals("/ip6/2001:db8::1/tcp/4000", Multiaddress("2001:db8::1", 4000).toString())
        assertEquals(Multiaddress("192.168.1.5", 7000), Multiaddress.fromString("192.168.1.5:7000"))
        assertEquals(Multiaddress("192.168.1.5", 9091), Multiaddress.fromString("192.168.1.5"))
    }
}
