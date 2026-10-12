package com.example.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RatchetRobustnessTest {
    private fun pair(): Pair<DoubleRatchetSession, DoubleRatchetSession> {
        val root = ByteArray(32) { it.toByte() }
        val privA = ByteArray(32) { (it + 7).toByte() }
        val privB = ByteArray(32) { (it + 1).toByte() }
        val pubA = Curve25519.x25519PublicFromPrivate(privA)
        val pubB = Curve25519.x25519PublicFromPrivate(privB)
        return DoubleRatchetSession("B", root, true, pubB, privA) to
            DoubleRatchetSession("A", root, false, pubA, privB)
    }

    private fun DoubleRatchetSession.text(e: DoubleRatchetSession.EncryptedEnvelope) = String(decrypt(e))

    @Test
    fun outOfOrderAndReplay() {
        val (a, b) = pair()
        val m1 = a.encrypt("1".toByteArray()); val m2 = a.encrypt("2".toByteArray()); val m3 = a.encrypt("3".toByteArray())
        assertEquals("1", b.text(m1))
        assertEquals("3", b.text(m3))
        assertEquals("2", b.text(m2))
        assertThrows(Exception::class.java) { b.decrypt(m2) }
    }

    @Test
    fun forgedMessageDoesNotCorruptSession() {
        val (a, b) = pair()
        val m = a.encrypt("x".toByteArray())
        val bad = m.copy(ciphertextHex = m.ciphertextHex.dropLast(2) + "00")
        assertThrows(Exception::class.java) { b.decrypt(bad) }
        val forged = m.copy(ratchetKeyHex = IdentityManager.bytesToHex(ByteArray(32) { 9 }), sequenceNumber = 5)
        assertThrows(Exception::class.java) { b.decrypt(forged) }
        assertEquals("x", b.text(m))
    }

    @Test
    fun oldChainMessageSurvivesRatchetStep() {
        val (a, b) = pair()
        val first = a.encrypt("a1".toByteArray())
        b.decrypt(first)
        val r1 = b.encrypt("b1".toByteArray()); val r2 = b.encrypt("b2".toByteArray())
        a.decrypt(r1)
        val n = a.encrypt("a2".toByteArray())
        b.decrypt(n)
        val y = b.encrypt("b3".toByteArray())
        assertEquals("b3", a.text(y))
        assertEquals("b2", a.text(r2))
    }

    @Test
    fun hugeSkipIsRejected() {
        val (a, b) = pair()
        val far = a.encrypt("far".toByteArray()).copy(sequenceNumber = 100_000)
        assertThrows(Exception::class.java) { b.decrypt(far) }
    }
}
