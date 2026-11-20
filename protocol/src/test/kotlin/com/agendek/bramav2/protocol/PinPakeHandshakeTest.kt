package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPakeHandshakeTest {

    private val gate = FakeGate()
    private val clientId = ByteArray(16) { 0x44 }
    private fun pin(text: String = "482915") = PairingSecret.Pin.parse(text)!!

    /** Prowadzi PAKE z FakeGate do końca; zwraca ostatni krok. */
    private fun run(hs: PinPakeHandshake): PakeStep {
        var out = gate.receive(hs.pendingRequest!!)!!
        while (true) {
            when (val step = hs.onFrame(out)) {
                is PakeStep.Send -> out = gate.receive(step.frame)!!
                else -> return step
            }
        }
    }

    @Test
    fun fullExchangeWithFakeGateYieldsSameKey() {
        val step = run(PinPakeHandshake(pin())) as PakeStep.KeyReady
        assertEquals(32, step.pinKey.size)
        assertArrayEquals(gate.pakeKey, step.pinKey)
    }

    @Test
    fun keyFeedsPairingHandshakeToGrant() {
        val key = (run(PinPakeHandshake(pin())) as PakeStep.KeyReady).pinKey
        val pair = PairingHandshake(key, gate.deviceId, clientId, CountingNonceSource(5))
        val confirm = pair.onFrame(gate.receive(pair.pendingRequest!!)!!) as HandshakeStep.Send
        assertTrue(pair.onFrame(gate.receive(confirm.frame)!!) is HandshakeStep.GrantReceived)
    }

    @Test
    fun wrongPinFailsAtPairChallenge() {
        gate.pin = "111111".toByteArray(Charsets.US_ASCII)
        val key = (run(PinPakeHandshake(pin())) as PakeStep.KeyReady).pinKey
        val pair = PairingHandshake(key, gate.deviceId, clientId, CountingNonceSource(5))
        // sterownik odrzuca PAIR_BEGIN (zły tag K_pin) — podpowiedź AUTH_FAILED
        assertEquals(HandshakeStep.Rejected(HandshakeRejectReason.AUTH_FAILED), pair.onFrame(gate.receive(pair.pendingRequest!!)!!))
        assertNull(gate.pakeKey) // K_pin jednorazowy
    }

    @Test
    fun reply1AssignsSessionIdUsedLater() {
        val hs = PinPakeHandshake(pin())
        assertEquals(0L, Frame.decode(hs.pendingRequest!!).header.sessionId)
        val reply1 = gate.receive(hs.pendingRequest!!)!!
        val send = hs.onFrame(reply1) as PakeStep.Send
        val sid = Frame.decode(reply1).header.sessionId
        assertTrue(sid != 0L)
        assertEquals(MessageType.PAKE_ROUND2, Frame.decode(send.frame).header.messageType)
        assertEquals(sid, Frame.decode(send.frame).header.sessionId)
    }

    @Test
    fun reply2WithOtherSessionIdIsIgnored() {
        val hs = PinPakeHandshake(pin())
        val send = hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as PakeStep.Send
        val reply2 = Frame.decode(gate.receive(send.frame)!!)
        val forged = Frame(reply2.header.copy(sessionId = reply2.header.sessionId + 1), reply2.body, ByteArray(0)).encode()
        assertTrue(hs.onFrame(forged) is PakeStep.Ignored)
        assertTrue(hs.onFrame(reply2.encode()) is PakeStep.KeyReady)
    }

    @Test
    fun duplicateReply1IsDuplicate() {
        val hs = PinPakeHandshake(pin())
        val reply1 = gate.receive(hs.pendingRequest!!)!!
        hs.onFrame(reply1)
        assertEquals(PakeStep.Duplicate, hs.onFrame(reply1))
    }

    @Test
    fun rejectWithMatchingSessionIdEndsAttempt() {
        val hs = PinPakeHandshake(pin())
        val reject = Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, 0, 0, 1), byteArrayOf(3), ByteArray(0)).encode()
        assertEquals(PakeStep.Rejected(HandshakeRejectReason.RATE_LIMITED), hs.onFrame(reject))
        assertNull(hs.pendingRequest)
    }

    @Test
    fun rejectWithOtherSessionIdIsIgnored() {
        val hs = PinPakeHandshake(pin())
        val reject = Frame(FrameHeader(MessageType.HANDSHAKE_REJECT.code, 9, 0, 1), byteArrayOf(1), ByteArray(0)).encode()
        assertTrue(hs.onFrame(reject) is PakeStep.Ignored)
    }

    @Test
    fun pendingRequestIsIdenticalOnRetransmission() {
        val hs = PinPakeHandshake(pin())
        assertArrayEquals(hs.pendingRequest, hs.pendingRequest)
        val send = hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as PakeStep.Send
        assertArrayEquals(send.frame, hs.pendingRequest)
    }

    @Test
    fun corruptedServerRoundFailsAttempt() {
        val hs = PinPakeHandshake(pin())
        val reply1 = Frame.decode(gate.receive(hs.pendingRequest!!)!!)
        val body = reply1.body.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertTrue(hs.onFrame(Frame(reply1.header, body, ByteArray(0)).encode()) is PakeStep.Failed)
        assertNull(hs.pendingRequest)
    }

    @Test
    fun kPinMatchesSpecFormula() {
        val s = ByteArray(32) { it.toByte() }
        val pt = ByteArray(10) { 7 }
        val expected = Crypto.hkdfExpand(Crypto.hkdfExtract(Crypto.sha256(pt), s), "BRAMA/2/PAKE/setup".toByteArray(Charsets.US_ASCII), 32)
        assertArrayEquals(expected, HandshakeFrames.pinSetupKey(s, pt))
    }

    @Test
    fun abortWipesState() {
        val hs = PinPakeHandshake(pin())
        hs.abort()
        assertNull(hs.pendingRequest)
        assertTrue(hs.onFrame(gate.receive(Frame(FrameHeader(MessageType.PAKE_ROUND1.code, 0, 0, 1), byteArrayOf(1), ByteArray(0)).encode()) ?: ByteArray(0)) !is PakeStep.KeyReady)
    }
}
