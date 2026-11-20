package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Wstępne sprawdzenie pliku obrazu na telefonie (15.5) na obrazie z builda firmware i jego mutacjach. */
class FirmwareImageTest {

    private val image: ByteArray =
        FirmwareImageTest::class.java.getResource("/firmware/brama.bin")!!.readBytes()

    private fun invalid(bytes: ByteArray): FirmwareImageError =
        (FirmwareImages.parse(bytes) as FirmwareImageResult.Invalid).reason

    private fun mutated(offset: Int, value: Int) = image.copyOf().also { it[offset] = value.toByte() }

    @Test
    fun buildImageIsAccepted() {
        val ok = FirmwareImages.parse(image) as FirmwareImageResult.Ok
        assertEquals("0.1.0", ok.image.version)
        assertEquals(1L, ok.image.secureVersion)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(image), ok.image.sha256)
        assertEquals(image.size, ok.image.bytes.size)
    }

    @Test
    fun parseCopiesInput() {
        val input = image.copyOf()
        val ok = FirmwareImages.parse(input) as FirmwareImageResult.Ok
        input.fill(0)
        assertEquals(0xE9, ok.image.bytes[0].toInt() and 0xff)
    }

    @Test
    fun emptyAndTooLargeAreRejected() {
        assertEquals(FirmwareImageError.EMPTY, invalid(ByteArray(0)))
        assertEquals(FirmwareImageError.TOO_LARGE, invalid(image.copyOf(OtaPayloads.MAX_IMAGE_SIZE.toInt() + 1)))
    }

    @Test
    fun headerFieldsAreChecked() {
        assertEquals(FirmwareImageError.BAD_MAGIC, invalid(mutated(0, 0xE8)))
        assertEquals(FirmwareImageError.WRONG_CHIP, invalid(mutated(12, 0x05)))
        assertEquals(FirmwareImageError.BAD_APP_DESC, invalid(mutated(32, 0x00)))
        assertEquals(FirmwareImageError.WRONG_PROJECT, invalid(mutated(32 + 48, 'x'.code)))
        // "brama" bez NUL na końcu (np. "bramax") to obcy projekt.
        assertEquals(FirmwareImageError.WRONG_PROJECT, invalid(mutated(32 + 48 + 5, 'x'.code)))
    }

    @Test
    fun shortFileIsTruncated() {
        assertEquals(FirmwareImageError.TRUNCATED, invalid(image.copyOf(100)))
        // Długość pierwszego segmentu poza plikiem.
        val bad = image.copyOf().also { it.putLe(24 + 4, 0x7fff_0000L, 4) }
        assertEquals(FirmwareImageError.TRUNCATED, invalid(bad))
    }

    @Test
    fun signatureBlockIsRequired() {
        assertEquals(FirmwareImageError.NO_SIGNATURE, invalid(image.copyOf(image.size - 68)))
        assertEquals(FirmwareImageError.NO_SIGNATURE, invalid(image.copyOf(image.size - 1)))
        assertEquals(FirmwareImageError.NO_SIGNATURE, invalid(image.copyOf(image.size + 16)))
        // Wersja bloku podpisu różna od 0.
        assertEquals(FirmwareImageError.NO_SIGNATURE, invalid(mutated(image.size - 68, 1)))
    }

    @Test
    fun toStringDoesNotDumpImage() {
        val ok = FirmwareImages.parse(image) as FirmwareImageResult.Ok
        assertTrue(ok.image.toString().length < 200)
    }
}
