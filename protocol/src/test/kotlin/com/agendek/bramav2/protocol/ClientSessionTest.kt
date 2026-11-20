package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSessionTest {

    private val gate = FakeGate()
    private val clientId = ByteArray(16) { 0x44 }
    private val clientKey = ByteArray(32) { 0x55 }.also { gate.clients[clientId.toHex()] = it }
    private lateinit var readyBytes: ByteArray
    private lateinit var challengeBytes: ByteArray

    private fun establish(): ClientSession {
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(1))
        challengeBytes = gate.receive(hs.pendingRequest!!)!!
        val confirm = (hs.onFrame(challengeBytes) as HandshakeStep.Send).frame
        readyBytes = gate.receive(confirm)!!
        return (hs.onFrame(readyBytes) as HandshakeStep.Established).session
    }

    @Test
    fun openTrueIsAcknowledgedWithSnapshot() {
        val s = establish()
        val req = s.send(SessionRequest.Open(autoCloseOnDisconnect = true))
        assertEquals(39, req.size)
        assertEquals(1L, Frame.decode(req).header.seq)
        val ev = s.onFrame(gate.receive(req)!!) as SessionEvent.CommandAcknowledged
        assertEquals(AckResult.OUTPUT_TRIGGERED, ev.ack.result)
        assertEquals(AutoClosePhase.MONITORING, s.latestSnapshot.phase)
        assertNull(s.pendingRequest)
        assertEquals(listOf("OPEN"), gate.impulses)
    }

    @Test
    fun seqIncrementsAcrossRequests() {
        val s = establish()
        s.onFrame(gate.receive(s.send(SessionRequest.Ping))!!)
        val close = s.send(SessionRequest.Close)
        assertEquals(2L, Frame.decode(close).header.seq)
        assertEquals(38, close.size)
    }

    @Test
    fun onlyOnePendingRequest() {
        val s = establish()
        s.send(SessionRequest.Close)
        assertThrows(IllegalStateException::class.java) { s.send(SessionRequest.Ping) }
    }

    @Test
    fun retransmissionUsesIdenticalBytesAndCachedAckResolvesOnce() {
        val s = establish()
        val req = s.send(SessionRequest.Close)
        val ack1 = gate.receive(req)!!
        val retransmit = s.pendingRequest!!
        assertArrayEquals(req, retransmit)
        val ack2 = gate.receive(retransmit)!!
        assertArrayEquals(ack1, ack2)
        assertEquals(listOf("CLOSE"), gate.impulses)

        assertTrue(s.onFrame(ack1) is SessionEvent.CommandAcknowledged)
        assertTrue(s.onFrame(ack2) is SessionEvent.Duplicate)
    }

    @Test
    fun pingYieldsPong() {
        val s = establish()
        val ev = s.onFrame(gate.receive(s.send(SessionRequest.Ping))!!)
        assertTrue(ev is SessionEvent.Pong)
    }

    @Test
    fun ruleStatusWhileAwaitingAckDoesNotResolveCommand() {
        val s = establish()
        val req = s.send(SessionRequest.Open(true))
        val status = gate.ruleStatus(AutoClosePhase.MONITORING)
        val ev = s.onFrame(status) as SessionEvent.RuleStatus
        assertTrue(ev.applied)
        assertArrayEquals(req, s.pendingRequest)
        assertTrue(s.onFrame(gate.receive(req)!!) is SessionEvent.CommandAcknowledged)
    }

    @Test
    fun olderSnapshotDoesNotOverwriteNewer() {
        val s = establish()
        val req = s.send(SessionRequest.Open(true))
        val ack = gate.receive(req)!!              // revision 2
        val status = gate.ruleStatus(AutoClosePhase.DISARMED)  // revision 3, seq po ACK
        // ACK dociera, potem status — kolejność seq zachowana
        s.onFrame(ack)
        s.onFrame(status)
        assertEquals(3L, s.latestSnapshot.revision)
        // duplikat starego ACK nie cofa stanu
        assertTrue(s.onFrame(ack) is SessionEvent.Duplicate)
        assertEquals(3L, s.latestSnapshot.revision)
        assertEquals(AutoClosePhase.DISARMED, s.latestSnapshot.phase)
    }

    @Test
    fun inOrderAckWithOlderRevisionDoesNotRollBackSnapshot() {
        val s = establish()
        s.send(SessionRequest.Close)
        val newer = RuleSnapshot(5, AutoClosePhase.MONITORING, OwnerRelation.OTHER, RuleReason.NONE, 0)
        val older = RuleSnapshot(4, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        assertTrue((s.onFrame(gate.sealRaw(MessageType.RULE_STATUS, 2, newer.encode())) as SessionEvent.RuleStatus).applied)
        val ack = s.onFrame(
            gate.sealRaw(MessageType.COMMAND_ACK, 3, CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 1, older).encode()),
        ) as SessionEvent.CommandAcknowledged
        assertEquals(false, ack.snapshotApplied)
        assertEquals(newer, s.latestSnapshot)
    }

    @Test
    fun lateDuplicateChallengeAfterReadyKeepsSession() {
        val s = establish()
        s.send(SessionRequest.Close)
        assertTrue(s.onFrame(challengeBytes) is SessionEvent.Duplicate)
        assertEquals(true, s.isValid)
        assertTrue(s.pendingRequest != null)
    }

    @Test
    fun duplicateReadyIsRecognised() {
        val s = establish()
        assertTrue(s.onFrame(readyBytes) is SessionEvent.Duplicate)
    }

    @Test
    fun sameSeqWithDifferentBytesEndsSession() {
        val s = establish()
        val req = s.send(SessionRequest.Close)
        val ack = gate.receive(req)!!
        s.onFrame(ack)
        val altered = ack.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertTrue(s.onFrame(altered) is SessionEvent.Failed)
        assertEquals(false, s.isValid)
        assertThrows(IllegalStateException::class.java) { s.send(SessionRequest.Ping) }
    }

    @Test
    fun seqGapEndsSession() {
        val s = establish()
        gate.ruleStatus(AutoClosePhase.MONITORING)             // seq 2, zgubiony
        val next = gate.ruleStatus(AutoClosePhase.DISARMED)    // seq 3
        assertTrue(s.onFrame(next) is SessionEvent.Failed)
    }

    @Test
    fun tamperedNextFrameEndsSession() {
        val s = establish()
        val st = gate.ruleStatus(AutoClosePhase.MONITORING)
        st[25] = (st[25].toInt() xor 1).toByte()
        assertTrue(s.onFrame(st) is SessionEvent.Failed)
    }

    @Test
    fun ackForDifferentRequestEndsSession() {
        val s = establish()
        s.send(SessionRequest.Close)
        // poprawnie zaszyfrowany ACK z błędną referencją request_seq
        val bad = gate.sealRaw(MessageType.COMMAND_ACK, 2,
            CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 99, gate.snapshot()).encode())
        val ev = s.onFrame(bad) as SessionEvent.Failed
        assertEquals(SessionRequest.Close, ev.unresolved)
    }

    @Test
    fun handshakeRejectAndOtherSessionFramesAreIgnored() {
        val s = establish()
        val reject = Frame(FrameHeader(0x0E, s.sessionId, 0, 1), byteArrayOf(1), ByteArray(0)).encode()
        assertTrue(s.onFrame(reject) is SessionEvent.Ignored)
        val foreign = Frame(FrameHeader(MessageType.RULE_STATUS.code, 77, 2, 15), ByteArray(15), ByteArray(16)).encode()
        assertTrue(s.onFrame(foreign) is SessionEvent.Ignored)
        assertEquals(true, s.isValid)
    }

    @Test
    fun clientToServerTypeFromServerEndsSession() {
        val s = establish()
        val reflected = gate.sealRaw(MessageType.OPEN, 2, byteArrayOf(1))
        assertTrue(s.onFrame(reflected) is SessionEvent.Failed)
    }

    private fun sessionKeyArrays(s: ClientSession): List<ByteArray> {
        val keys = s.javaClass.declaredFields.map { f -> f.isAccessible = true; f.get(s) }.filterIsInstance<SessionKeys>().single()
        return listOf(keys.c2sKey, keys.s2cKey, keys.c2sIv, keys.s2cIv)
    }

    @Test
    fun invalidateWipesSessionKeys() {
        val s = establish()
        assertTrue(sessionKeyArrays(s).any { a -> a.any { it != 0.toByte() } })
        s.invalidate()
        assertTrue(sessionKeyArrays(s).all { a -> a.all { it == 0.toByte() } })
    }

    @Test
    fun protocolFailureWipesSessionKeys() {
        val s = establish()
        gate.ruleStatus(AutoClosePhase.MONITORING) // seq 2 zgubiony
        assertTrue(s.onFrame(gate.ruleStatus(AutoClosePhase.DISARMED)) is SessionEvent.Failed)
        assertTrue(sessionKeyArrays(s).all { a -> a.all { it == 0.toByte() } })
    }

    @Test
    fun revisionIsComparedAsUnsigned64() {
        val s = establish()
        val high = RuleSnapshot(Long.MIN_VALUE, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0) // 2^63
        val higher = high.copy(revision = Long.MIN_VALUE + 1, phase = AutoClosePhase.MONITORING, ownerRelation = OwnerRelation.OTHER)
        assertTrue((s.onFrame(gate.sealRaw(MessageType.RULE_STATUS, 2, high.encode())) as SessionEvent.RuleStatus).applied)
        assertTrue((s.onFrame(gate.sealRaw(MessageType.RULE_STATUS, 3, higher.encode())) as SessionEvent.RuleStatus).applied)
        val lower = RuleSnapshot(5, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        assertEquals(false, (s.onFrame(gate.sealRaw(MessageType.RULE_STATUS, 4, lower.encode())) as SessionEvent.RuleStatus).applied)
        assertEquals(higher, s.latestSnapshot)
    }

    @Test
    fun invalidateMarksPendingAsUnknown() {
        val s = establish()
        s.send(SessionRequest.Close)
        val lost = s.invalidate()
        assertEquals(SessionRequest.Close, lost)
        assertEquals(false, s.isValid)
    }
}
