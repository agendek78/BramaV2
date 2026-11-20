package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Żądania OTA w sesji READY (15.5): wspólny licznik i kolejka z komendami, dopasowanie OTA_ACK. */
class ClientSessionOtaTest {

    private val gate = FakeGate()
    private val clientId = ByteArray(16) { 0x44 }
    private val clientKey = ByteArray(32) { 0x55 }.also { gate.clients[clientId.toHex()] = it }

    private fun establish(): ClientSession {
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(1))
        val challenge = gate.receive(hs.pendingRequest!!)!!
        val confirm = (hs.onFrame(challenge) as HandshakeStep.Send).frame
        return (hs.onFrame(gate.receive(confirm)!!) as HandshakeStep.Established).session
    }

    private fun roundTrip(s: ClientSession, request: SessionRequest): SessionEvent = s.onFrame(gate.receive(s.send(request))!!)

    @Test
    fun fullTransferIsAcknowledgedInOrder() {
        val s = establish()
        val begin = SessionRequest.OtaBegin(471, ByteArray(32) { 7 })
        val beginBytes = s.send(begin)
        assertEquals(74, beginBytes.size) // ramka 74 B (15.5)
        val ev = s.onFrame(gate.receive(beginBytes)!!) as SessionEvent.OtaAcknowledged
        assertSame(begin, ev.request)
        assertEquals(OtaResult.OK, ev.ack.result)
        assertEquals(1L, ev.ack.requestSeq)

        val chunk = SessionRequest.OtaData(0, ByteArray(OtaPayloads.MAX_DATA) { it.toByte() })
        val chunkBytes = s.send(chunk)
        assertEquals(512, chunkBytes.size) // największa ramka (10.1)
        assertEquals(470L, (s.onFrame(gate.receive(chunkBytes)!!) as SessionEvent.OtaAcknowledged).ack.nextOffset)
        assertEquals(471L, (roundTrip(s, SessionRequest.OtaData(470, byteArrayOf(1))) as SessionEvent.OtaAcknowledged).ack.nextOffset)
        val end = roundTrip(s, SessionRequest.OtaEnd) as SessionEvent.OtaAcknowledged
        assertEquals(MessageType.OTA_END.code, end.ack.requestTypeCode)
        assertNull(s.pendingRequest)
    }

    @Test
    fun otaSharesSeqWithCommands() {
        val s = establish()
        roundTrip(s, SessionRequest.OtaBegin(1, ByteArray(32)))
        val close = s.send(SessionRequest.Close)
        assertEquals(2L, Frame.decode(close).header.seq)
        assertTrue(s.onFrame(gate.receive(close)!!) is SessionEvent.CommandAcknowledged)
        assertEquals(3L, Frame.decode(s.send(SessionRequest.OtaAbort)).header.seq)
    }

    @Test
    fun otaAckDoesNotChangeSnapshot() {
        val s = establish()
        val before = s.latestSnapshot
        roundTrip(s, SessionRequest.OtaBegin(1, ByteArray(32)))
        assertSame(before, s.latestSnapshot)
    }

    @Test
    fun duplicateOtaAckIsDuplicate() {
        val s = establish()
        val req = s.send(SessionRequest.OtaEnd)
        val ack = gate.receive(req)!!
        assertTrue(s.onFrame(ack) is SessionEvent.OtaAcknowledged)
        assertEquals(SessionEvent.Duplicate, s.onFrame(ack))
    }

    @Test
    fun retransmissionIsIdenticalBytes() {
        val s = establish()
        val req = s.send(SessionRequest.OtaData(0, byteArrayOf(1, 2, 3)))
        assertArrayEquals(req, s.pendingRequest)
    }

    @Test
    fun otaAckWithWrongReferenceFailsSession() {
        val s = establish()
        s.send(SessionRequest.OtaEnd)
        val wrongSeq = gate.sealRaw(MessageType.OTA_ACK, 2, OtaAck(MessageType.OTA_END.code, OtaResult.OK, 5, 0).encode())
        val ev = s.onFrame(wrongSeq) as SessionEvent.Failed
        assertEquals(SessionRequest.OtaEnd, ev.unresolved)
    }

    @Test
    fun otaAckWithWrongTypeFailsSession() {
        val s = establish()
        s.send(SessionRequest.OtaEnd)
        val wrongType = gate.sealRaw(MessageType.OTA_ACK, 2, OtaAck(MessageType.OTA_ABORT.code, OtaResult.OK, 1, 0).encode())
        assertTrue(s.onFrame(wrongType) is SessionEvent.Failed)
    }

    @Test
    fun commandAckForOtaRequestFailsSession() {
        val s = establish()
        s.send(SessionRequest.OtaAbort)
        val ack = gate.sealRaw(MessageType.COMMAND_ACK, 2, CommandAck(MessageType.OTA_ABORT.code, AckResult.OUTPUT_TRIGGERED, 1, gate.snapshot()).encode())
        assertTrue(s.onFrame(ack) is SessionEvent.Failed)
    }

    @Test
    fun otaAckForCommandOrPingFailsSession() {
        val s = establish()
        s.send(SessionRequest.Close)
        val ack = gate.sealRaw(MessageType.OTA_ACK, 2, OtaAck(MessageType.CLOSE.code, OtaResult.OK, 1, 0).encode())
        assertTrue(s.onFrame(ack) is SessionEvent.Failed)

        val s2 = establish() // nowa sesja z tym samym sterownikiem
        s2.send(SessionRequest.Ping)
        val ackPing = gate.sealRaw(MessageType.OTA_ACK, 2, OtaAck(MessageType.PING.code, OtaResult.OK, 1, 0).encode())
        assertTrue(s2.onFrame(ackPing) is SessionEvent.Failed)
    }

    @Test
    fun otaAckWithoutPendingRequestFailsSession() {
        val s = establish()
        val ack = gate.sealRaw(MessageType.OTA_ACK, 2, OtaAck(MessageType.OTA_END.code, OtaResult.OK, 1, 0).encode())
        assertTrue(s.onFrame(ack) is SessionEvent.Failed)
    }

    @Test
    fun unknownResultCodeIsImageRejected() {
        val s = establish()
        s.send(SessionRequest.OtaEnd)
        val payload = OtaAck(MessageType.OTA_END.code, OtaResult.OK, 1, 0).encode().also { it[1] = 0x7f }
        val ev = s.onFrame(gate.sealRaw(MessageType.OTA_ACK, 2, payload)) as SessionEvent.OtaAcknowledged
        assertEquals(OtaResult.IMAGE_REJECTED, ev.ack.result)
    }

    @Test
    fun otaDataPayloadIsOffsetAndChunk() {
        val s = establish()
        gate.receive(s.send(SessionRequest.OtaData(940, byteArrayOf(9, 8))))
        // FakeGate dodaje długość danych bez 4-bajtowego offsetu.
        assertEquals(2L, gate.otaNextOffset)
    }
}
