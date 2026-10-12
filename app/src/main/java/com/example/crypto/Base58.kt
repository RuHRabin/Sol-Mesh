package com.example.crypto

import java.math.BigInteger
import java.util.Arrays

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) {
            this[ALPHABET[i].code] = i
        }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }
        val encoded = StringBuilder()
        var num = BigInteger(1, input)
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val divRem = num.divideAndRemainder(base)
            num = divRem[0]
            val rem = divRem[1].toInt()
            encoded.append(ALPHABET[rem])
        }
        for (i in 0 until zeros) {
            encoded.append(ALPHABET[0])
        }
        return encoded.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) {
            zeros++
        }
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (i in zeros until input.length) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Illegal character $c in Base58" }
            num = num.multiply(base).add(BigInteger.valueOf(digit.toLong()))
        }
        val numBytes = if (num == BigInteger.ZERO) ByteArray(0) else num.toByteArray()
        val stripLeadingZero = numBytes.isNotEmpty() && numBytes[0].toInt() == 0
        val actualBytes = if (stripLeadingZero) numBytes.copyOfRange(1, numBytes.size) else numBytes
        val result = ByteArray(zeros + actualBytes.size)
        System.arraycopy(actualBytes, 0, result, zeros, actualBytes.size)
        return result
    }
}
