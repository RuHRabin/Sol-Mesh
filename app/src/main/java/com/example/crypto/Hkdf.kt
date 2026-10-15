package com.example.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * RFC 5869 HMAC-based Extract-and-Expand Key Derivation Function (HKDF) using SHA-256.
 */
object Hkdf {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val actualInfo = info ?: ByteArray(0)
        val n = ceil(length.toDouble() / HASH_LEN).toInt()
        require(n <= 255) { "Requested HKDF length is too large" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val output = ByteArrayOutputStream()
        var t = ByteArray(0)

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(actualInfo)
            mac.update(i.toByte())
            t = mac.doFinal()

            val bytesRemaining = length - output.size()
            val bytesToWrite = minOf(t.size, bytesRemaining)
            output.write(t, 0, bytesToWrite)
        }

        return output.toByteArray()
    }

    fun deriveKey(salt: ByteArray?, ikm: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        return expand(prk, info, length)
    }
}
