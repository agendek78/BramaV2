package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTest {

    private val sid = 0x0102030405060708L

    @Test
    fun openHeaderMatchesSpecExample() {
        val h = FrameHeader(MessageType.OPEN.code, sid, seq = 1, payloadLength = 1).encode()
        assertEquals("47 42 02 01 08 07 06 05 04 03 02 01 01 00 00 00 00 00 00 00 01 00".hexToBytes().toHex(), h.toHex())
    }

    @Test
    fun closeHeaderMatchesSpecExample() {
        val h = FrameHeader(MessageType.CLOSE.code, sid, seq = 1, payloadLength = 0).encode()
        assertEquals("47 42 02 02 08 07 06 05 04 03 02 01 01 00 00 00 00 00 00 00 00 00".hexToBytes().toHex(), h.toHex())
    }

    @Test
    fun frameRoundTripsWithGcmTag() {
        val frame = Frame(FrameHeader(MessageType.COMMAND_ACK.code, sid, 2, 25), ByteArray(25) { it.toByte() }, ByteArray(16) { 0x55 })
        val bytes = frame.encode()
        assertEquals(22 + 25 + 16, bytes.size)
        val decoded = Frame.decode(bytes)
        assertEquals(frame.header, decoded.header)
        assertArrayEquals(frame.body, decoded.body)
        assertArrayEquals(frame.tag, decoded.tag)
    }

    @Test
    fun handshakeFrameCarriesHmacTag() {
        val f = Frame(FrameHeader(MessageType.SESSION_BEGIN.code, 0, 0, 48), ByteArray(48), ByteArray(32))
        assertEquals(22 + 48 + 32, f.encode().size)
        assertEquals(32, Frame.decode(f.encode()).tag.size)
    }

    @Test
    fun handshakeRejectHasNoTagAndIs23Bytes() {
        val f = Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, 0, 0, 1), byteArrayOf(1), ByteArray(0))
        assertEquals(23, f.encode().size)
        assertEquals(0, Frame.decode(f.encode()).tag.size)
    }

    @Test
    fun unknownTypeIsParsedAsGcmFrame() {
        val f = Frame(FrameHeader(0x77, sid, 3, 2), byteArrayOf(1, 2), ByteArray(16))
        assertEquals(null, Frame.decode(f.encode()).header.messageType)
    }

    @Test
    fun rejectsBadMagicVersionAndLengths() {
        val good = Frame(FrameHeader(MessageType.CLOSE.code, sid, 1, 0), ByteArray(0), ByteArray(16)).encode()

        fun mutated(i: Int, v: Int) = good.copyOf().also { it[i] = v.toByte() }
        assertThrows(ProtocolException::class.java) { Frame.decode(mutated(0, 0x48)) }
        assertThrows(ProtocolException::class.java) { Frame.decode(mutated(2, 0x01)) }   // ramka wersji 1
        assertThrows(ProtocolException::class.java) { Frame.decode(good + 0) }           // nadmiar bajtów
        assertThrows(ProtocolException::class.java) { Frame.decode(good.copyOf(good.size - 1)) }
        assertThrows(ProtocolException::class.java) { Frame.decode(good.copyOf(10)) }
    }

    @Test
    fun rejectsFramesLongerThan512() {
        val f = Frame(FrameHeader(0x77, sid, 1, 512 - 22 - 16 + 1), ByteArray(512 - 22 - 16 + 1), ByteArray(16))
        assertThrows(ProtocolException::class.java) { f.encode() }
    }

    @Test
    fun enforcesSeqRulesPerType() {
        // handshake HMAC: seq = 0
        assertThrows(ProtocolException::class.java) {
            Frame.decode(rawFrame(MessageType.SESSION_CONFIRM.code, sid, 1, 0, 32))
        }
        // GCM: seq >= 1
        assertThrows(ProtocolException::class.java) {
            Frame.decode(rawFrame(MessageType.OPEN.code, sid, 0, 1, 16))
        }
        // reject: seq 0, payload 1
        assertThrows(ProtocolException::class.java) {
            Frame.decode(rawFrame(MessageType.HANDSHAKE_REJECT.code, 0, 0, 2, 0))
        }
    }

    @Test
    fun beginMustHaveZeroSessionId() {
        assertThrows(ProtocolException::class.java) {
            Frame.decode(rawFrame(MessageType.PAIR_BEGIN.code, sid, 0, 48, 32))
        }
    }

    @Test
    fun knownTypesHaveSpecifiedDirections() {
        assertEquals(Direction.C2S, MessageType.OPEN.direction)
        assertEquals(Direction.S2C, MessageType.COMMAND_ACK.direction)
        assertEquals(Direction.S2C, MessageType.HANDSHAKE_REJECT.direction)
        assertEquals(MessageType.RULE_STATUS, MessageType.fromCode(0x40))
        assertEquals(null, MessageType.fromCode(0x99))
    }

    private fun rawFrame(type: Int, sessionId: Long, seq: Long, payloadLen: Int, tagLen: Int): ByteArray =
        FrameHeader(type, sessionId, seq, payloadLen).encode() + ByteArray(payloadLen) + ByteArray(tagLen)

    @Test
    fun sessionPhaseServerTypesAreExactlyPongStatusAckClientListAndOtaAck() {
        val s2cSession = MessageType.entries.filter { it.direction == Direction.S2C && it.phase == MessagePhase.SESSION }
        assertEquals(setOf(MessageType.PONG, MessageType.RULE_STATUS, MessageType.COMMAND_ACK, MessageType.CLIENT_LIST, MessageType.OTA_ACK), s2cSession.toSet())
        assertEquals(MessagePhase.PAIR, MessageType.PAIR_GRANT.phase)
        assertEquals(MessagePhase.HANDSHAKE, MessageType.SESSION_READY.phase)
    }

    @Test
    fun pakeFramesHaveNoTagAndSeqZero() {
        val f = Frame(FrameHeader(MessageType.PAKE_ROUND1.code, 0, 0, 330), ByteArray(330) { it.toByte() }, ByteArray(0))
        val bytes = f.encode()
        assertEquals(22 + 330, bytes.size)
        val d = Frame.decode(bytes)
        assertEquals(MessageType.PAKE_ROUND1, d.header.messageType)
        assertEquals(0, d.tag.size)
        assertArrayEquals(f.body, d.body)
        assertEquals(TagKind.NONE, MessageType.PAKE_ROUND2_REPLY.tagKind)
        assertEquals(true, MessageType.PAKE_ROUND2.isPake)
        assertEquals(false, MessageType.PAIR_BEGIN.isPake)
    }

    @Test
    fun pakeTypesHaveSpecifiedCodesAndDirections() {
        assertEquals(MessageType.PAKE_ROUND1, MessageType.fromCode(0x26))
        assertEquals(MessageType.PAKE_ROUND1_REPLY, MessageType.fromCode(0x27))
        assertEquals(MessageType.PAKE_ROUND2, MessageType.fromCode(0x28))
        assertEquals(MessageType.PAKE_ROUND2_REPLY, MessageType.fromCode(0x29))
        assertEquals(Direction.C2S, MessageType.PAKE_ROUND1.direction)
        assertEquals(Direction.S2C, MessageType.PAKE_ROUND1_REPLY.direction)
        assertEquals(Direction.C2S, MessageType.PAKE_ROUND2.direction)
        assertEquals(Direction.S2C, MessageType.PAKE_ROUND2_REPLY.direction)
    }

    @Test
    fun pakeWithNonZeroSeqIsRejected() {
        assertThrows(ProtocolException::class.java) { Frame.decode(rawFrame(MessageType.PAKE_ROUND2.code, 5, 1, 10, 0)) }
    }

    @Test
    fun pakeRound1WithNonZeroSessionIdIsRejected() {
        assertThrows(ProtocolException::class.java) { Frame.decode(rawFrame(MessageType.PAKE_ROUND1.code, 7, 0, 10, 0)) }
        Frame.decode(rawFrame(MessageType.PAKE_ROUND1_REPLY.code, 7, 0, 10, 0)) // odpowiedź nadaje session_id
    }

    @Test
    fun pakeWithEmptyPayloadIsRejected() {
        assertThrows(ProtocolException::class.java) { Frame.decode(rawFrame(MessageType.PAKE_ROUND1.code, 0, 0, 0, 0)) }
    }

    @Test
    fun handshakeRejectStillRequiresOneByte() {
        assertThrows(ProtocolException::class.java) { Frame.decode(rawFrame(MessageType.HANDSHAKE_REJECT.code, 0, 0, 10, 0)) }
    }
}
