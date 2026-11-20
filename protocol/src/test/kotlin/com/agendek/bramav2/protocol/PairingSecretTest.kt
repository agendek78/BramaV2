package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSecretTest {

    @Test
    fun pinParseRejectsNonSixDigits() {
        assertNull(PairingSecret.Pin.parse("12345"))
        assertNull(PairingSecret.Pin.parse("1234567"))
        assertNull(PairingSecret.Pin.parse("12a456"))
        assertNull(PairingSecret.Pin.parse(" 123456"))
        assertNull(PairingSecret.Pin.parse("１２３４５６"))
        assertEquals("000123", String(PairingSecret.Pin.parse("000123")!!.pin, Charsets.US_ASCII))
    }

    @Test
    fun pinCopiesInput() {
        val input = "482915".toByteArray(Charsets.US_ASCII)
        val pin = PairingSecret.Pin(input)
        input.fill(0)
        assertArrayEquals("482915".toByteArray(Charsets.US_ASCII), pin.pin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pinConstructorValidates() {
        PairingSecret.Pin("48291".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun wipeZeroesPin() {
        val pin = PairingSecret.Pin.parse("482915")!!
        pin.wipe()
        assertTrue(pin.pin.all { it == 0.toByte() })
    }

    @Test
    fun wipeZeroesQrSecrets() {
        val qr = ProvisioningQr(ByteArray(16) { 1 }, ByteArray(32) { 2 }, "482915".toByteArray(Charsets.US_ASCII))
        PairingSecret.Qr(qr).wipe()
        assertTrue(qr.setupKey.all { it == 0.toByte() } && qr.pin.all { it == 0.toByte() })
    }

    @Test
    fun toStringHidesPin() {
        assertEquals(false, PairingSecret.Pin.parse("482915").toString().contains("482915"))
    }
}
