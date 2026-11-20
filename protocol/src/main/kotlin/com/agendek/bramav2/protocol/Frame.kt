package com.agendek.bramav2.protocol

/** Nagłówek kompletnej ramki, 22 B (10.3). Pola wielobajtowe little-endian. */
data class FrameHeader(
    val typeCode: Int,
    val sessionId: Long,
    val seq: Long,
    val payloadLength: Int,
) {
    val messageType: MessageType? get() = MessageType.fromCode(typeCode)

    fun encode(): ByteArray {
        require(typeCode in 0..0xff && payloadLength in 0..0xffff)
        val h = ByteArray(SIZE)
        h[0] = MAGIC_0
        h[1] = MAGIC_1
        h[2] = VERSION
        h[3] = typeCode.toByte()
        h.putLe(4, sessionId, 8)
        h.putLe(12, seq, 8)
        h.putLe(20, payloadLength.toLong(), 2)
        return h
    }

    companion object {
        const val SIZE = 22
        const val VERSION: Byte = 0x02
        private const val MAGIC_0: Byte = 0x47 // 'G'
        private const val MAGIC_1: Byte = 0x42 // 'B'

        fun decode(bytes: ByteArray): FrameHeader {
            if (bytes.size < SIZE) throw ProtocolException("Ramka krótsza niż nagłówek")
            if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) throw ProtocolException("Zły magic")
            if (bytes[2] != VERSION) throw ProtocolException("Nieobsługiwana wersja ${bytes[2]}")
            return FrameHeader(
                typeCode = bytes[3].toInt() and 0xff,
                sessionId = bytes.getLe(4, 8),
                seq = bytes.getLe(12, 8),
                payloadLength = bytes.getLe(20, 2).toInt(),
            )
        }
    }
}

/**
 * Kompletna ramka: `H || body || tag`. `body` to jawny payload handshake albo ciphertext AES-GCM
 * tej samej długości co jawny payload; `tag` to HMAC (32 B), tag GCM (16 B) lub brak.
 */
class Frame(val header: FrameHeader, val body: ByteArray, val tag: ByteArray) {

    fun encode(): ByteArray {
        validate(header, body.size, tag.size)
        return header.encode() + body + tag
    }

    companion object {
        const val MAX_SIZE = 512

        fun decode(bytes: ByteArray): Frame {
            if (bytes.size > MAX_SIZE) throw ProtocolException("Ramka dłuższa niż $MAX_SIZE B")
            val header = FrameHeader.decode(bytes)
            val tagLen = MessageType.tagKindOf(header.typeCode).length
            val bodyEnd = FrameHeader.SIZE + header.payloadLength
            if (bytes.size != bodyEnd + tagLen) throw ProtocolException("Niezgodna długość ramki")
            validate(header, header.payloadLength, tagLen)
            return Frame(header, bytes.copyOfRange(FrameHeader.SIZE, bodyEnd), bytes.copyOfRange(bodyEnd, bytes.size))
        }

        private fun validate(header: FrameHeader, bodyLen: Int, tagLen: Int) {
            if (bodyLen != header.payloadLength) throw ProtocolException("payload_length ≠ długość treści")
            val kind = MessageType.tagKindOf(header.typeCode)
            if (tagLen != kind.length) throw ProtocolException("Zła długość tagu")
            if (FrameHeader.SIZE + bodyLen + tagLen > MAX_SIZE) throw ProtocolException("Ramka dłuższa niż $MAX_SIZE B")
            when (kind) {
                TagKind.HMAC -> if (header.seq != 0L) throw ProtocolException("Handshake wymaga seq = 0")
                TagKind.GCM -> if (header.seq == 0L) throw ProtocolException("Wiadomość GCM wymaga seq >= 1")
                TagKind.NONE -> when {
                    header.seq != 0L -> throw ProtocolException("Ramka bez tagu wymaga seq = 0")
                    header.messageType == MessageType.HANDSHAKE_REJECT && bodyLen != 1 -> throw ProtocolException("Zły HANDSHAKE_REJECT")
                    bodyLen == 0 -> throw ProtocolException("Pusta runda PAKE")
                }
            }
            val type = header.messageType
            if ((type?.isBegin == true || type == MessageType.PAKE_ROUND1) && header.sessionId != 0L) {
                throw ProtocolException("BEGIN wymaga session_id = 0")
            }
        }
    }
}
