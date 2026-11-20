package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OtaPayloadsTest {

    private val key = ByteArray(32) { 1 }
    private val iv = ByteArray(4) { 2 }

    private fun frameSize(type: MessageType, payload: ByteArray) =
        GcmFrames.seal(key, iv, type.code, 7, 1, payload).encode().size

    @Test
    fun otaTypesAreSessionGcmWithSpecCodes() {
        assertEquals(0x50, MessageType.OTA_BEGIN.code)
        assertEquals(0x51, MessageType.OTA_DATA.code)
        assertEquals(0x52, MessageType.OTA_END.code)
        assertEquals(0x53, MessageType.OTA_ABORT.code)
        assertEquals(0x81, MessageType.OTA_ACK.code)
        for (t in listOf(MessageType.OTA_BEGIN, MessageType.OTA_DATA, MessageType.OTA_END, MessageType.OTA_ABORT)) {
            assertEquals(Direction.C2S, t.direction)
            assertEquals(TagKind.GCM, t.tagKind)
            assertEquals(MessagePhase.SESSION, t.phase)
        }
        assertEquals(Direction.S2C, MessageType.OTA_ACK.direction)
    }

    @Test
    fun otaFrameLengthsMatchSpec() {
        val begin = OtaPayloads.begin(1_000_000, ByteArray(32) { 9 })
        assertEquals(36, begin.size)
        assertEquals(74, frameSize(MessageType.OTA_BEGIN, begin))
        val data = OtaPayloads.data(0, ByteArray(OtaPayloads.MAX_DATA))
        assertEquals(474, data.size)
        assertEquals(512, frameSize(MessageType.OTA_DATA, data))
        val ack = OtaAck(MessageType.OTA_DATA.code, OtaResult.OK, 3, 470).encode()
        assertEquals(14, ack.size)
        assertEquals(52, frameSize(MessageType.OTA_ACK, ack))
    }

    @Test
    fun beginEncodesSizeLittleEndianThenSha() {
        val sha = ByteArray(32) { it.toByte() }
        assertEquals("40420f00" + sha.toHex(), OtaPayloads.begin(1_000_000, sha).toHex())
    }

    @Test
    fun dataRejectsEmptyAndOversizedChunks() {
        assertThrows(IllegalArgumentException::class.java) { OtaPayloads.data(0, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { OtaPayloads.data(0, ByteArray(471)) }
        assertEquals("05000000aa", OtaPayloads.data(5, byteArrayOf(0xaa.toByte())).toHex())
    }

    @Test
    fun ackRoundTripsEveryResult() {
        assertEquals(11, OtaResult.entries.size)
        for (r in OtaResult.entries) {
            val ack = OtaAck(MessageType.OTA_END.code, r, 0x0102030405L, 0x01020304)
            assertEquals(ack, OtaAck.decode(ack.encode()))
        }
        assertArrayEquals(
            "52070500000000000000".hexToBytes() + "04030201".hexToBytes(),
            OtaAck(0x52, OtaResult.BAD_OFFSET, 5, 0x01020304).encode(),
        )
    }

    @Test
    fun ackWithUnknownResultDecodesAsImageRejected() {
        val bytes = OtaAck(0x52, OtaResult.OK, 1, 0).encode().also { it[1] = 0x7f }
        assertEquals(OtaResult.IMAGE_REJECTED, OtaAck.decode(bytes).result)
    }

    @Test
    fun ackWithWrongLengthIsRejected() {
        assertThrows(ProtocolException::class.java) { OtaAck.decode(ByteArray(13)) }
    }
}
