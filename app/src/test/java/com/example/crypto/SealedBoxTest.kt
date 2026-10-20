package com.example.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SealedBoxTest {

    private class Person {
        private val kp = Curve25519.ed25519GenerateKeyPair()
        val edPriv: ByteArray = kp.first
        val edPub: ByteArray = kp.second
        val dhPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dhPub: ByteArray = Curve25519.x25519PublicFromPrivate(dhPriv)
    }

    private val message = OfflineMessage("msg-123", 1_700_000_000_000L, "Hello Bob, \u0987\u09A8\u09CD\u099F\u09BE\u09B0\u09A8\u09C7\u099F \u2713")

    private fun seal(from: Person, to: Person, m: OfflineMessage = message) =
        SealedBox.seal(from.edPriv, from.edPub, to.dhPub, m)

    @Test
    fun recipientCanOpenWhatWasSealedForThem() {
        val alice = Person()
        val bob = Person()
        val opened = SealedBox.open(seal(alice, bob), bob.dhPriv, bob.dhPub)!!
        assertEquals(message.text, opened.message.text)
        assertEquals(message.id, opened.message.id)
        assertEquals(message.timestamp, opened.message.timestamp)
        assertTrue(opened.senderPublicKey.contentEquals(alice.edPub))
    }

    @Test
    fun theServerOrAnyoneElseCannotOpenIt() {
        val alice = Person()
        val bob = Person()
        val eve = Person()
        val sealed = seal(alice, bob)
        assertNull(SealedBox.open(sealed, eve.dhPriv, eve.dhPub))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Hello Bob"))
    }

    @Test
    fun changingAnySingleByteIsDetected() {
        val alice = Person()
        val bob = Person()
        val sealed = seal(alice, bob, OfflineMessage("m", 1L, "x"))
        for (i in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[i] = (tampered[i].toInt() xor 1).toByte()
            assertNull("accepted a change at byte $i", SealedBox.open(tampered, bob.dhPriv, bob.dhPub))
        }
    }

    @Test
    fun cannotPretendToBeSomeoneElseOrRedirectToAnotherRecipient() {
        val alice = Person()
        val bob = Person()
        val eve = Person()
        val carol = Person()
        val sealed = seal(alice, bob)

        val forged = sealed.copyOf()
        System.arraycopy(eve.edPub, 0, forged, 1, 32) // claim Eve sent it
        assertNull(SealedBox.open(forged, bob.dhPriv, bob.dhPub))

        assertNull(SealedBox.open(sealed, carol.dhPriv, carol.dhPub)) // redirected to Carol
    }

    @Test
    fun brokenInputIsRejectedWithoutCrashing() {
        val alice = Person()
        val bob = Person()
        val sealed = seal(alice, bob)
        assertNull(SealedBox.open(ByteArray(0), bob.dhPriv, bob.dhPub))
        assertNull(SealedBox.open(sealed.copyOf(50), bob.dhPriv, bob.dhPub))
    }

    @Test
    fun everySealIsDifferentEvenForTheSameMessage() {
        val alice = Person()
        val bob = Person()
        assertFalse(seal(alice, bob).contentEquals(seal(alice, bob)))
    }

    @Test
    fun limitsAreEnforced() {
        val alice = Person()
        val bob = Person()
        assertThrows(IllegalArgumentException::class.java) { OfflineMessage("m", 1, "a".repeat(8001)).encode() }
        assertThrows(IllegalArgumentException::class.java) { OfflineMessage("", 1, "x").encode() }
        assertThrows(IllegalArgumentException::class.java) { SealedBox.seal(alice.edPriv, alice.edPub, ByteArray(32), message) }
        val biggest = OfflineMessage("m", 1, "a".repeat(8000))
        assertEquals(8000, SealedBox.open(seal(alice, bob, biggest), bob.dhPriv, bob.dhPub)!!.message.text.length)
    }
}
