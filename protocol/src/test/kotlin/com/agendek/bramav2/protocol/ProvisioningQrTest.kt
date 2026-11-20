package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class ProvisioningQrTest {

    private val idHex = "00112233445566778899aabbccddeeff"
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    private val pin = "012345"

    private fun parse(s: String) = ProvisioningQr.parse(s)

    @Test
    fun parsesValidQr() {
        val r = parse("brama://provision?v=2&id=$idHex&p=$pin&k=$k") as QrParseResult.Ok
        assertEquals(idHex, r.qr.deviceId.toHex())
        assertEquals(key.toHex(), r.qr.setupKey.toHex())
    }

    @Test
    fun acceptsAnyParameterOrder() {
        assertEquals(true, parse("brama://provision?k=$k&id=$idHex&v=2&p=$pin") is QrParseResult.Ok)
    }

    @Test
    fun rejectsOtherVersion() {
        assertEquals(QrError.UNSUPPORTED_VERSION, (parse("brama://provision?v=1&id=$idHex&p=$pin&k=$k") as QrParseResult.Invalid).error)
        assertEquals(QrError.UNSUPPORTED_VERSION, (parse("brama://provision?v=3&id=$idHex&p=$pin&k=$k") as QrParseResult.Invalid).error)
    }

    @Test
    fun newerVersionWithDifferentFieldsIsUnsupportedVersionNotMalformed() {
        assertEquals(QrError.UNSUPPORTED_VERSION, err("brama://provision?v=3&id=$idHex&p=$pin&k=$k&x=1"))
        assertEquals(QrError.UNSUPPORTED_VERSION, err("brama://provision?v=3&id=$idHex"))
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&v=3&id=$idHex&p=$pin&k=$k"))
        assertEquals(QrError.MALFORMED, err("brama://provision?id=$idHex&p=$pin&k=$k"))
    }

    @Test
    fun rejectsRepeatedMissingAndUnknownFields() {
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&id=$idHex&id=$idHex&p=$pin&k=$k"))
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&id=$idHex"))
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&id=$idHex&p=$pin&k=$k&x=1"))
        assertEquals(QrError.MALFORMED, err("https://provision?v=2&id=$idHex&p=$pin&k=$k"))
        assertEquals(QrError.MALFORMED, err("brama://other?v=2&id=$idHex&p=$pin&k=$k"))
    }

    @Test
    fun rejectsBadDeviceIdEncoding() {
        assertEquals(QrError.INVALID_DEVICE_ID, err("brama://provision?v=2&id=${idHex.uppercase()}&p=$pin&k=$k"))
        assertEquals(QrError.INVALID_DEVICE_ID, err("brama://provision?v=2&id=${idHex.dropLast(2)}&p=$pin&k=$k"))
        assertEquals(QrError.INVALID_DEVICE_ID, err("brama://provision?v=2&id=${idHex}00&p=$pin&k=$k"))
        assertEquals(QrError.INVALID_DEVICE_ID, err("brama://provision?v=2&id=${idHex.dropLast(1)}g&p=$pin&k=$k"))
    }

    @Test
    fun rejectsBadKeyEncoding() {
        assertEquals(QrError.INVALID_KEY, err("brama://provision?v=2&id=$idHex&p=$pin&k=$k="))
        assertEquals(QrError.INVALID_KEY, err("brama://provision?v=2&id=$idHex&p=$pin&k=${k.dropLast(1)}"))
        val std = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { -1 }) // zawiera '/'
        assertEquals(QrError.INVALID_KEY, err("brama://provision?v=2&id=$idHex&p=$pin&k=$std"))
        // niekanoniczne końcowe bity
        val last = k.last()
        val alt = if (last == 'A') 'B' else 'A'
        val nonCanonical = k.dropLast(1) + alt
        val reEncoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Base64.getUrlDecoder().decode(nonCanonical))
        if (reEncoded != nonCanonical) {
            assertEquals(QrError.INVALID_KEY, err("brama://provision?v=2&id=$idHex&p=$pin&k=$nonCanonical"))
        }
    }

    @Test
    fun invalidResultDoesNotExposeSecret() {
        val r = parse("brama://provision?v=2&id=$idHex&p=$pin&k=$k=")
        assertEquals(false, r.toString().contains(k))
    }

    @Test
    fun okResultToStringDoesNotExposeSecret() {
        val r = parse("brama://provision?v=2&id=$idHex&p=$pin&k=$k")
        assertEquals(false, r.toString().contains(k))
        assertEquals(false, r.toString().contains(key.toHex()))
    }

    private fun err(s: String) = (parse(s) as QrParseResult.Invalid).error

    @Test
    fun pinFieldIsParsed() {
        val r = parse("brama://provision?v=2&id=$idHex&k=$k&p=$pin") as QrParseResult.Ok
        assertEquals("012345", String(r.qr.pin, Charsets.US_ASCII))
    }

    @Test
    fun missingPinIsMalformed() {
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&id=$idHex&k=$k"))
    }

    @Test
    fun pinWithFiveOrSevenDigitsIsInvalidPin() {
        assertEquals(QrError.INVALID_PIN, err("brama://provision?v=2&id=$idHex&k=$k&p=01234"))
        assertEquals(QrError.INVALID_PIN, err("brama://provision?v=2&id=$idHex&k=$k&p=0123456"))
    }

    @Test
    fun pinWithNonDigitIsInvalidPin() {
        assertEquals(QrError.INVALID_PIN, err("brama://provision?v=2&id=$idHex&k=$k&p=12a456"))
        assertEquals(QrError.INVALID_PIN, err("brama://provision?v=2&id=$idHex&k=$k&p=１２３４５６"))
    }

    @Test
    fun repeatedPinFieldIsMalformed() {
        assertEquals(QrError.MALFORMED, err("brama://provision?v=2&id=$idHex&k=$k&p=$pin&p=$pin"))
    }

    @Test
    fun toStringHidesPin() {
        val r = parse("brama://provision?v=2&id=$idHex&k=$k&p=482915") as QrParseResult.Ok
        assertEquals(false, r.qr.toString().contains("482915"))
        assertEquals(false, r.toString().contains("482915"))
    }
}
