package com.agendek.bramav2.protocol

import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Kryptograficzne prymitywy protokołu (11.2, 12.3): HMAC-SHA-256, HKDF-SHA-256 (RFC 5869), AES-256-GCM. */
object Crypto {
    const val HMAC_LEN = 32
    const val GCM_TAG_LEN = 16
    const val GCM_NONCE_LEN = 12

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** `MAC(K, D, X) = HMAC-SHA-256(K, ASCII(D) || 0x00 || X)`. */
    fun mac(key: ByteArray, domain: String, x: ByteArray): ByteArray =
        hmacSha256(key, domain.toByteArray(Charsets.US_ASCII) + 0 + x)

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HMAC_LEN)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val input = t + info + counter.toByte()
            t.fill(0)
            t = hmacSha256(prk, input)
            input.fill(0)
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        t.fill(0)
        return out
    }

    /** Zwraca `ciphertext || tag[16]`. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = gcm(Cipher.ENCRYPT_MODE, key, nonce)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Przyjmuje `ciphertext || tag[16]`; zwraca `null` przy niepoprawnym tagu. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < GCM_TAG_LEN) return null
        val cipher = gcm(Cipher.DECRYPT_MODE, key, nonce)
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(sealed)
        } catch (_: BadPaddingException) { // AEADBadTagException lub ogólniejszy wyjątek innego dostawcy JCA
            null
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == 32) { "AES-256 wymaga klucza 32 B" }
        require(nonce.size == GCM_NONCE_LEN) { "Nonce GCM musi mieć 12 B" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
        }
    }
}
