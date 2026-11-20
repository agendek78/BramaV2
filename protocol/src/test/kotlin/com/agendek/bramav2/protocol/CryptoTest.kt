package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoTest {

    @Test
    fun hmacSha256MatchesRfc4231Case1() {
        val key = ByteArray(20) { 0x0b }
        val mac = Crypto.hmacSha256(key, "Hi There".toByteArray(Charsets.US_ASCII))
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac.toHex())
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()

        val prk = Crypto.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())

        val okm = Crypto.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun aesGcmMatchesZeroVector() {
        val sealed = Crypto.aesGcmSeal(
            key = ByteArray(32),
            nonce = ByteArray(12),
            plaintext = ByteArray(16),
            aad = ByteArray(0),
        )
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", sealed.toHex())
    }

    @Test
    fun aesGcmOpenReturnsNullOnTamperedTag() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val aad = byteArrayOf(9, 9)
        val sealed = Crypto.aesGcmSeal(key, nonce, byteArrayOf(1, 2, 3), aad)

        assertEquals("010203", Crypto.aesGcmOpen(key, nonce, sealed, aad)!!.toHex())

        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertEquals(null, Crypto.aesGcmOpen(key, nonce, sealed, aad))
    }

    @Test
    fun domainMacPrefixesAsciiDomainAndNul() {
        val key = ByteArray(32) { 7 }
        val x = byteArrayOf(1, 2, 3)
        val expected = Crypto.hmacSha256(key, "BRAMA/2/X".toByteArray(Charsets.US_ASCII) + 0 + x)
        assertEquals(expected.toHex(), Crypto.mac(key, "BRAMA/2/X", x).toHex())
    }

    @Test
    fun constantTimeEqualsComparesContent() {
        assertEquals(true, Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertEquals(false, Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertEquals(false, Crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}
