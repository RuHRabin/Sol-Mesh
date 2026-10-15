package com.example.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * Pure Kotlin cryptographic engine implementing:
 * - RFC 7748 X25519 (Montgomery curve Diffie-Hellman key exchange)
 * - RFC 8032 Ed25519 (Edwards curve digital signatures)
 * Standardized, self-contained, and zero external JNI dependency.
 */
object Curve25519 {
    val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.valueOf(2).pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    private val B_Y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
    private val B_X = recoverX(B_Y, 0)
    val BASE_POINT = Point(B_X, B_Y)

    data class Point(val x: BigInteger, val y: BigInteger) {
        companion object {
            val ZERO = Point(BigInteger.ZERO, BigInteger.ONE)
        }

        fun add(other: Point): Point {
            if (this == ZERO) return other
            if (other == ZERO) return this

            val x1x2 = x.multiply(other.x).mod(P)
            val y1y2 = y.multiply(other.y).mod(P)
            val dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

            val x3Num = x.multiply(other.y).add(y.multiply(other.x)).mod(P)
            val x3Den = BigInteger.ONE.add(dxxyy).mod(P)
            val x3 = x3Num.multiply(x3Den.modInverse(P)).mod(P)

            val y3Num = y1y2.add(x1x2).mod(P)
            val y3Den = BigInteger.ONE.subtract(dxxyy).mod(P)
            val y3 = y3Num.multiply(y3Den.modInverse(P)).mod(P)

            return Point(x3, y3)
        }

        fun multiply(scalar: BigInteger): Point {
            var result = ZERO
            var current = this
            var s = scalar.mod(L)
            while (s > BigInteger.ZERO) {
                if (s.testBit(0)) {
                    result = result.add(current)
                }
                current = current.add(current)
                s = s.shiftRight(1)
            }
            return result
        }

        fun encode(): ByteArray {
            val bytes = toLittleEndianByteArray(y, 32)
            if (x.testBit(0)) {
                bytes[31] = (bytes[31].toInt() or 0x80).toByte()
            }
            return bytes
        }
    }

    private fun recoverX(y: BigInteger, sign: Int): BigInteger {
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        var x = u.multiply(v.modInverse(P)).mod(P)
        x = x.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)

        if (x.multiply(x).subtract(u.multiply(v.modInverse(P))).mod(P) != BigInteger.ZERO) {
            x = x.multiply(I).mod(P)
        }

        if (x.testBit(0) != (sign != 0)) {
            x = P.subtract(x).mod(P)
        }
        return x
    }

    fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val copy = bytes.copyOf()
        val sign = (copy[31].toInt() and 0x80) shr 7
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        val y = fromLittleEndian(copy)
        if (y >= P) return null
        return try {
            val x = recoverX(y, sign)
            Point(x, y)
        } catch (_: Exception) {
            null
        }
    }

    // --- RFC 7748 X25519 Key Exchange ---

    fun x25519ScalarMult(scalarBytes: ByteArray, uCoords: ByteArray): ByteArray {
        val clampedScalar = scalarBytes.copyOf(32)
        clampedScalar[0] = (clampedScalar[0].toInt() and 248).toByte()
        clampedScalar[31] = (clampedScalar[31].toInt() and 127).toByte()
        clampedScalar[31] = (clampedScalar[31].toInt() or 64).toByte()

        val k = fromLittleEndian(clampedScalar)
        val u = fromLittleEndian(uCoords).mod(P)
        val a24 = BigInteger.valueOf(121665)

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            val shouldSwap = swap xor kt
            if (shouldSwap != 0) {
                var temp = x2; x2 = x3; x3 = temp
                temp = z2; z2 = z3; z3 = temp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).mod(P).pow(2).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(a24.multiply(e).mod(P))).mod(P)
        }

        if (swap != 0) {
            val temp = x2; x2 = x3; x3 = temp
            val tempZ = z2; z2 = z3; z3 = tempZ
        }

        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return toLittleEndianByteArray(result, 32)
    }

    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        val basePointU = ByteArray(32)
        basePointU[0] = 9
        return x25519ScalarMult(privateKey, basePointU)
    }

    // --- RFC 8032 Ed25519 Digital Signatures ---

    fun ed25519GenerateKeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        val publicKey = ed25519PublicKey(privateKey)
        return Pair(privateKey, publicKey)
    }

    fun ed25519PublicKey(privateKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        val h = md.digest(privateKey)
        val s = clampScalar(h.copyOfRange(0, 32))
        val a = fromLittleEndian(s)
        val aPoint = BASE_POINT.multiply(a)
        return aPoint.encode()
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        val h = md.digest(privateKey)
        val s = clampScalar(h.copyOfRange(0, 32))
        val a = fromLittleEndian(s)
        val aPoint = BASE_POINT.multiply(a)
        val aEncoded = aPoint.encode()

        // r = SHA-512(h[32..63] || message) mod L
        md.reset()
        md.update(h, 32, 32)
        md.update(message)
        val rDigest = md.digest()
        val r = fromLittleEndian(rDigest).mod(L)

        // R = r * B
        val rPoint = BASE_POINT.multiply(r)
        val rEncoded = rPoint.encode()

        // k = SHA-512(R || A || message) mod L
        md.reset()
        md.update(rEncoded)
        md.update(aEncoded)
        md.update(message)
        val kDigest = md.digest()
        val k = fromLittleEndian(kDigest).mod(L)

        // S = (r + k * a) mod L
        val sNum = r.add(k.multiply(a)).mod(L)
        val sEncoded = toLittleEndianByteArray(sNum, 32)

        val signature = ByteArray(64)
        System.arraycopy(rEncoded, 0, signature, 0, 32)
        System.arraycopy(sEncoded, 0, signature, 32, 32)
        return signature
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val rBytes = signature.copyOfRange(0, 32)
        val sBytes = signature.copyOfRange(32, 64)

        val s = fromLittleEndian(sBytes)
        if (s >= L) return false

        val aPoint = decodePoint(publicKey) ?: return false
        val rPoint = decodePoint(rBytes) ?: return false

        val md = MessageDigest.getInstance("SHA-512")
        md.update(rBytes)
        md.update(publicKey)
        md.update(message)
        val kDigest = md.digest()
        val k = fromLittleEndian(kDigest).mod(L)

        val sB = BASE_POINT.multiply(s)
        val kA = aPoint.multiply(k)
        val rPlusKA = rPoint.add(kA)

        return sB.encode().contentEquals(rPlusKA.encode())
    }

    private fun clampScalar(s: ByteArray): ByteArray {
        val clamped = s.copyOf(32)
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()
        return clamped
    }

    fun fromLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = ByteArray(bytes.size)
        for (i in bytes.indices) {
            reversed[i] = bytes[bytes.size - 1 - i]
        }
        return BigInteger(1, reversed)
    }

    fun toLittleEndianByteArray(num: BigInteger, length: Int): ByteArray {
        val be = num.toByteArray()
        val le = ByteArray(length)
        var beIndex = be.size - 1
        var leIndex = 0
        while (beIndex >= 0 && leIndex < length) {
            le[leIndex] = be[beIndex]
            beIndex--
            leIndex++
        }
        return le
    }
}
