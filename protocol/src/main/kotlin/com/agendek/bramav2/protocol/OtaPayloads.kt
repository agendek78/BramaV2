package com.agendek.bramav2.protocol

/** Wyniki OTA_ACK (15.5). */
enum class OtaResult(val code: Int) {
    OK(0x00), BUSY(0x01), RULE_ACTIVE(0x02), LINK_TOO_SMALL(0x03), TOO_LARGE(0x04), IMAGE_REJECTED(0x05),
    DOWNGRADE(0x06), BAD_OFFSET(0x07), NOT_IN_PROGRESS(0x08), STORAGE_FAILURE(0x09), INVALID_ARGUMENT(0x0A);

    companion object {
        /** Nieznany kod telefon traktuje jak IMAGE_REJECTED (15.5). */
        fun fromCode(code: Int): OtaResult = entries.firstOrNull { it.code == code } ?: IMAGE_REJECTED
    }
}

/** `request_type[1] || result[1] || request_seq[8 LE] || next_offset[4 LE]` — 14 B (15.5). */
data class OtaAck(val requestTypeCode: Int, val result: OtaResult, val requestSeq: Long, val nextOffset: Long) {
    fun encode(): ByteArray =
        byteArrayOf(requestTypeCode.toByte(), result.code.toByte()) + le64(requestSeq) + le64(nextOffset).copyOf(4)

    companion object {
        const val SIZE = 14

        fun decode(bytes: ByteArray): OtaAck {
            if (bytes.size != SIZE) throw ProtocolException("OTA_ACK musi mieć $SIZE B")
            return OtaAck(
                requestTypeCode = bytes[0].toInt() and 0xff,
                result = OtaResult.fromCode(bytes[1].toInt() and 0xff),
                requestSeq = bytes.getLe(2, 8),
                nextOffset = bytes.getLe(10, 4),
            )
        }
    }
}

/** Jawne payloady aktualizacji firmware przed szyfrowaniem (15.5). */
object OtaPayloads {
    /** Największy kawałek: ramka 512 B = nagłówek 22 + offset 4 + dane + tag 16. */
    const val MAX_DATA = 470

    /** Największy obraz: slot `0x1E0000` B (tabela partycji 15.5). */
    const val MAX_IMAGE_SIZE = 0x1E0000L

    val END_PAYLOAD = ByteArray(0)
    val ABORT_PAYLOAD = ByteArray(0)

    fun begin(imageSize: Long, sha256: ByteArray): ByteArray {
        require(imageSize in 1..0xffffffffL) { "Zły rozmiar obrazu" }
        require(sha256.size == 32) { "SHA-256 musi mieć 32 B" }
        return le64(imageSize).copyOf(4) + sha256
    }

    fun data(offset: Long, chunk: ByteArray): ByteArray {
        require(offset in 0..0xffffffffL) { "Zły offset" }
        require(chunk.size in 1..MAX_DATA) { "Kawałek musi mieć 1–$MAX_DATA B" }
        return le64(offset).copyOf(4) + chunk
    }
}
