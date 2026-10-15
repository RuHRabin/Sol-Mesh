package com.example.p2p

import com.example.crypto.Base58
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Kademlia DHT 256-bit Routing Table with K-buckets (k=20).
 * Calculates XOR distance metric: d(x, y) = x ^ y.
 */
class DhtRoutingTable(val localPeerId: String) {
    companion object {
        const val K = 20
        const val BUCKET_COUNT = 256

        fun idToBigInteger(peerId: String): BigInteger {
            return try {
                val decoded = Base58.decode(peerId)
                val rawBytes = if (decoded.size >= 34 && decoded[0].toInt() == 0x12 && decoded[1].toInt() == 0x20) {
                    decoded.copyOfRange(2, 34)
                } else if (decoded.size == 32) {
                    decoded
                } else {
                    MessageDigest.getInstance("SHA-256").digest(peerId.toByteArray(Charsets.UTF_8))
                }
                BigInteger(1, rawBytes)
            } catch (_: Exception) {
                val md = MessageDigest.getInstance("SHA-256")
                BigInteger(1, md.digest(peerId.toByteArray(Charsets.UTF_8)))
            }
        }

        fun xorDistance(idA: String, idB: String): BigInteger {
            return idToBigInteger(idA).xor(idToBigInteger(idB))
        }
    }

    data class Contact(
        val peerId: String,
        val multiaddresses: List<String>,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private val localIdInt = idToBigInteger(localPeerId)
    private val buckets: Array<MutableList<Contact>> = Array(BUCKET_COUNT) { mutableListOf() }
    private val lock = Any()

    private fun getBucketIndex(peerId: String): Int {
        val distance = idToBigInteger(peerId).xor(localIdInt)
        if (distance == BigInteger.ZERO) return 0
        // Find highest bit index (0 to 255)
        val bitLength = distance.bitLength()
        return minOf(bitLength - 1, BUCKET_COUNT - 1)
    }

    fun update(contact: Contact) {
        if (contact.peerId == localPeerId) return
        synchronized(lock) {
            val idx = getBucketIndex(contact.peerId)
            val bucket = buckets[idx]
            val existingIdx = bucket.indexOfFirst { it.peerId == contact.peerId }
            if (existingIdx != -1) {
                // Move to end (most recently seen)
                bucket.removeAt(existingIdx)
                bucket.add(contact.copy(lastSeen = System.currentTimeMillis()))
            } else if (bucket.size < K) {
                bucket.add(contact)
            } else {
                // Bucket full: replace oldest if needed or preserve
                bucket.removeAt(0)
                bucket.add(contact)
            }
        }
    }

    fun remove(peerId: String) {
        synchronized(lock) {
            val idx = getBucketIndex(peerId)
            buckets[idx].removeAll { it.peerId == peerId }
        }
    }

    fun getClosestNodes(targetId: String, count: Int = K): List<Contact> {
        synchronized(lock) {
            val targetInt = idToBigInteger(targetId)
            val all = mutableListOf<Contact>()
            for (bucket in buckets) {
                all.addAll(bucket)
            }
            return all.sortedBy { idToBigInteger(it.peerId).xor(targetInt) }.take(count)
        }
    }

    fun getAllContacts(): List<Contact> {
        synchronized(lock) {
            val list = mutableListOf<Contact>()
            for (bucket in buckets) {
                list.addAll(bucket)
            }
            return list
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return buckets.sumOf { it.size }
        }
    }

    fun clear() {
        synchronized(lock) {
            for (bucket in buckets) {
                bucket.clear()
            }
        }
    }
}
