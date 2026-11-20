package com.agendek.bramav2.protocol

/**
 * Plik obrazu firmware po wstępnym sprawdzeniu na telefonie (15.5). [bytes] to cały plik razem z blokiem
 * podpisu — tyle wysyła się sterownikowi (`image_size` w OTA_BEGIN), a [sha256] liczy się z całości.
 * Podpisu telefon nie weryfikuje; `secure_version` porównuje sterownik (`OTA_DOWNGRADE`).
 */
class FirmwareImage internal constructor(val bytes: ByteArray, val version: String, val secureVersion: Long, val sha256: ByteArray) {
    override fun toString(): String = "FirmwareImage(version=$version, secureVersion=$secureVersion, size=${bytes.size})"
}

enum class FirmwareImageError { EMPTY, TOO_LARGE, BAD_MAGIC, WRONG_CHIP, BAD_APP_DESC, WRONG_PROJECT, TRUNCATED, NO_SIGNATURE }

sealed interface FirmwareImageResult {
    class Ok(val image: FirmwareImage) : FirmwareImageResult
    data class Invalid(val reason: FirmwareImageError) : FirmwareImageResult
}

/**
 * Format obrazu aplikacji ESP-IDF: `esp_image_header_t` (24 B), segmenty (8 B nagłówka + dane), dopełnienie do 16 B
 * z bajtem sumy kontrolnej, opcjonalny SHA-256 (32 B), a przy podpisie ECDSA bez Secure Boot — blok podpisu 68 B
 * (wersja LE32 = 0, podpis 64 B). Pierwszy segment zaczyna się od `esp_app_desc_t` (offset 32). Kontrole jak
 * `brama_ota_check_header` po stronie sterownika, plus kompletność pliku i obecność bloku podpisu.
 */
object FirmwareImages {
    private const val MAGIC = 0xE9
    private const val CHIP_ID_ESP32 = 0x0000L
    private const val HEADER_LEN = 24
    private const val SEGMENT_HEADER_LEN = 8
    private const val APP_DESC_OFFSET = 32
    private const val APP_DESC_MAGIC = 0xABCD5432L
    private const val APP_DESC_LEN = 256
    private const val VERSION_OFFSET = 16
    private const val PROJECT_OFFSET = 48
    private const val FIELD_LEN = 32
    private const val HASH_LEN = 32
    private const val SIGNATURE_BLOCK_LEN = 68
    private const val PROJECT = "brama"

    fun parse(input: ByteArray): FirmwareImageResult {
        if (input.isEmpty()) return invalid(FirmwareImageError.EMPTY)
        if (input.size > OtaPayloads.MAX_IMAGE_SIZE) return invalid(FirmwareImageError.TOO_LARGE)
        val bytes = input.copyOf()
        if (bytes.size < APP_DESC_OFFSET + APP_DESC_LEN) return invalid(FirmwareImageError.TRUNCATED)
        if (bytes[0].toInt() and 0xff != MAGIC) return invalid(FirmwareImageError.BAD_MAGIC)
        if (bytes.getLe(12, 2) != CHIP_ID_ESP32) return invalid(FirmwareImageError.WRONG_CHIP)
        if (bytes.getLe(APP_DESC_OFFSET, 4) != APP_DESC_MAGIC) return invalid(FirmwareImageError.BAD_APP_DESC)
        val project = bytes.copyOfRange(APP_DESC_OFFSET + PROJECT_OFFSET, APP_DESC_OFFSET + PROJECT_OFFSET + FIELD_LEN)
        val expected = PROJECT.toByteArray(Charsets.US_ASCII)
        if (!project.copyOf(expected.size).contentEquals(expected) || project[expected.size] != 0.toByte()) {
            return invalid(FirmwareImageError.WRONG_PROJECT)
        }
        val end = imageEnd(bytes) ?: return invalid(FirmwareImageError.TRUNCATED)
        if (bytes.size.toLong() != end + SIGNATURE_BLOCK_LEN || bytes.getLe(end.toInt(), 4) != 0L) {
            return invalid(FirmwareImageError.NO_SIGNATURE)
        }
        return FirmwareImageResult.Ok(
            FirmwareImage(
                bytes = bytes,
                version = cString(bytes, APP_DESC_OFFSET + VERSION_OFFSET),
                secureVersion = bytes.getLe(APP_DESC_OFFSET + 4, 4),
                sha256 = Crypto.sha256(bytes),
            ),
        )
    }

    /** Koniec obrazu przed blokiem podpisu albo `null`, gdy segmenty wychodzą poza plik. */
    private fun imageEnd(bytes: ByteArray): Long? {
        var offset = HEADER_LEN.toLong()
        repeat(bytes[1].toInt() and 0xff) {
            if (offset + SEGMENT_HEADER_LEN > bytes.size) return null
            offset += SEGMENT_HEADER_LEN + bytes.getLe(offset.toInt() + 4, 4)
            if (offset > bytes.size) return null
        }
        // Bajt sumy kontrolnej na ostatniej pozycji bloku 16 B.
        offset = (offset or 15) + 1
        if (bytes[23].toInt() != 0) offset += HASH_LEN
        return offset.takeIf { it <= bytes.size }
    }

    private fun cString(bytes: ByteArray, offset: Int): String {
        val field = bytes.copyOfRange(offset, offset + FIELD_LEN)
        val len = field.indexOf(0).let { if (it < 0) FIELD_LEN else it }
        // Jak `GattProfile.firmwareRevisionOf` — porównanie wersji po restarcie nie może zależeć od białych znaków.
        return String(field, 0, len, Charsets.US_ASCII).trim()
    }

    private fun invalid(reason: FirmwareImageError) = FirmwareImageResult.Invalid(reason)
}
