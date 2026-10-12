package com.example.p2p

import com.example.crypto.Curve25519
import com.example.crypto.IdentityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class KademliaDhtTest {

    @Test
    fun testPeerIdDerivation() {
        val (_, pubKey) = Curve25519.ed25519GenerateKeyPair()
        val peerId = IdentityManager.derivePeerId(pubKey)
        assertNotNull(peerId)
        assertTrue("PeerID should be non-empty Base58 string", peerId.length > 20)

        // Derivation must be deterministic
        val peerId2 = IdentityManager.derivePeerId(pubKey)
        assertEquals("PeerID derivation must be deterministic", peerId, peerId2)
    }

    @Test
    fun testXorMetricProperties() {
        val idA = "12D3KooWNodeA111111111111111111111111111111111111111"
        val idB = "12D3KooWNodeB222222222222222222222222222222222222222"
        val idC = "12D3KooWNodeC333333333333333333333333333333333333333"

        // 1. Identity: d(x, x) == 0
        val distAA = DhtRoutingTable.xorDistance(idA, idA)
        assertEquals(BigInteger.ZERO, distAA)

        // 2. Symmetry: d(x, y) == d(y, x)
        val distAB = DhtRoutingTable.xorDistance(idA, idB)
        val distBA = DhtRoutingTable.xorDistance(idB, idA)
        assertEquals(distAB, distBA)

        // 3. Positivity: d(x, y) > 0 when x != y
        assertTrue(distAB > BigInteger.ZERO)
    }

    @Test
    fun testRoutingTableClosestNodesLookup() {
        val localId = "12D3KooWLocalNode000000000000000000000000000000000000"
        val table = DhtRoutingTable(localId)

        val contacts = listOf(
            DhtRoutingTable.Contact("12D3KooWNode111111111111111111111111111111111111111", listOf("/ip4/10.0.0.1/tcp/9091")),
            DhtRoutingTable.Contact("12D3KooWNode222222222222222222222222222222222222222", listOf("/ip4/10.0.0.2/tcp/9091")),
            DhtRoutingTable.Contact("12D3KooWNode333333333333333333333333333333333333333", listOf("/ip4/10.0.0.3/tcp/9091"))
        )

        for (c in contacts) {
            table.update(c)
        }

        assertEquals(3, table.size())

        val closest = table.getClosestNodes(localId, 2)
        assertEquals(2, closest.size)
    }

    @Test
    fun testPeerRecordSignedVerification() {
        val (privKey, pubKey) = Curve25519.ed25519GenerateKeyPair()
        val peerId = IdentityManager.derivePeerId(pubKey)
        val timestamp = System.currentTimeMillis()
        val ttl = 300L
        val addresses = listOf("/ip4/192.168.1.10/tcp/9091", "/ip4/203.0.113.5/tcp/9091")
        val sortedAddrs = addresses.sorted().joinToString(",")

        val raw = "$peerId|Alice|${IdentityManager.bytesToHex(pubKey)}||$sortedAddrs|$timestamp|$ttl"
        val sig = Curve25519.ed25519Sign(privKey, raw.toByteArray(Charsets.UTF_8))

        val record = PeerRecord(
            peerId = peerId,
            displayName = "Alice",
            publicKeyHex = IdentityManager.bytesToHex(pubKey),
            dhPublicKeyHex = "",
            addresses = addresses,
            timestamp = timestamp,
            ttlSeconds = ttl,
            signatureHex = IdentityManager.bytesToHex(sig)
        )

        assertTrue("Valid PeerRecord signature must verify", record.verifySignature())
        assertFalse("Recent PeerRecord must not be expired", record.isExpired())

        // Expired record test
        val expiredRecord = record.copy(timestamp = System.currentTimeMillis() - 400_000, ttlSeconds = 300)
        assertTrue("Past timestamp record must be expired", expiredRecord.isExpired())

        // Tampered record test
        val tamperedRecord = record.copy(displayName = "Eve")
        assertFalse("Tampered record must fail verification", tamperedRecord.verifySignature())
    }
}
