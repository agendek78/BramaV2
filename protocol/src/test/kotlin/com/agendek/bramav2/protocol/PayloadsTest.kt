package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayloadsTest {

    private fun snapshotBytes(rev: Long, phase: Int, owner: Int, reason: Int, remaining: Long) =
        le64(rev) + byteArrayOf(phase.toByte(), owner.toByte(), reason.toByte()) + le64(remaining).copyOf(4)

    @Test
    fun snapshotRoundTripsAs15Bytes() {
        val s = RuleSnapshot(0x0102030405060708L, AutoClosePhase.WAITING, OwnerRelation.SELF, RuleReason.LINK_LOST, 29_500)
        val bytes = s.encode()
        assertEquals("08070605040302010201013c730000", bytes.toHex())
        assertEquals(s, RuleSnapshot.decode(bytes))
    }

    @Test
    fun snapshotAcceptsEveryValidPhaseReasonCombination() {
        val valid = listOf(
            snapshotBytes(1, 0, 0, 0, 0),
            snapshotBytes(1, 0, 0, 2, 0),        // DISARMED/REBOOT po zimnym starcie
            snapshotBytes(1, 1, 1, 0, 0),
            snapshotBytes(1, 1, 2, 0, 0),
            snapshotBytes(1, 2, 2, 1, 30_000),
            snapshotBytes(1, 3, 1, 2, 30_000),
            snapshotBytes(1, 4, 1, 3, 0),
            snapshotBytes(1, 5, 1, 4, 0),
            snapshotBytes(1, 5, 2, 5, 0),
            snapshotBytes(1, 5, 1, 6, 0),
        )
        valid.forEach { RuleSnapshot.decode(it) }
    }

    @Test
    fun snapshotRejectsInconsistentOrUnknownFields() {
        val invalid = listOf(
            snapshotBytes(1, 0, 1, 0, 0),        // DISARMED z właścicielem
            snapshotBytes(1, 0, 0, 1, 0),        // DISARMED z LINK_LOST
            snapshotBytes(1, 0, 0, 0, 5),        // DISARMED z remaining
            snapshotBytes(1, 1, 0, 0, 0),        // MONITORING bez właściciela
            snapshotBytes(1, 1, 1, 1, 0),        // MONITORING z reason
            snapshotBytes(1, 1, 1, 0, 100),      // remaining poza WAITING/BOOT_WAIT
            snapshotBytes(1, 2, 1, 2, 1000),     // WAITING z REBOOT
            snapshotBytes(1, 3, 1, 1, 1000),     // BOOT_WAIT z LINK_LOST
            snapshotBytes(1, 4, 1, 4, 0),        // BLOCKED bez INTERLOCK
            snapshotBytes(1, 5, 1, 3, 0),        // FAULT z INTERLOCK
            snapshotBytes(1, 6, 1, 0, 0),        // nieznana faza
            snapshotBytes(1, 1, 3, 0, 0),        // nieznany owner
            snapshotBytes(1, 5, 1, 7, 0),        // nieznany reason
        )
        invalid.forEach { bytes -> assertThrows(ProtocolException::class.java) { RuleSnapshot.decode(bytes) } }
        assertThrows(ProtocolException::class.java) { RuleSnapshot.decode(ByteArray(14)) }
        assertThrows(ProtocolException::class.java) { RuleSnapshot.decode(ByteArray(16)) }
    }

    @Test
    fun ackDecodes25Bytes() {
        val snap = RuleSnapshot(7, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0)
        val bytes = byteArrayOf(0x01, 0x00) + le64(42) + snap.encode()
        val ack = CommandAck.decode(bytes)
        assertEquals(CommandAck(MessageType.OPEN.code, AckResult.OUTPUT_TRIGGERED, 42, snap), ack)
        assertArrayEquals(bytes, ack.encode())
    }

    @Test
    fun ackRejectsWrongLengthAndUnknownResult() {
        val snap = RuleSnapshot(7, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0).encode()
        assertThrows(ProtocolException::class.java) { CommandAck.decode(byteArrayOf(1, 0) + le64(1) + snap + 0) }
        assertThrows(ProtocolException::class.java) { CommandAck.decode(byteArrayOf(1, 14) + le64(1) + snap) }
    }

    @Test
    fun everyAckResultHasSpecCode() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
            listOf(
                AckResult.OUTPUT_TRIGGERED, AckResult.BUSY, AckResult.LEASE_OWNED_BY_OTHER, AckResult.INTERLOCK_BLOCKED,
                AckResult.STORAGE_FAILURE, AckResult.IO_FAULT, AckResult.INVALID_ARGUMENT, AckResult.UNSUPPORTED_TYPE,
                AckResult.WINDOW_OPENED, AckResult.CLIENT_REMOVED, AckResult.NAME_SET, AckResult.CLIENT_NOT_FOUND,
                AckResult.CANNOT_REMOVE_SELF, AckResult.CLIENT_OWNS_RULE,
            ).map { it.code },
        )
    }

    @Test
    fun handshakeRejectUnknownCodeMeansAuthFailed() {
        assertEquals(HandshakeRejectReason.NO_CAPACITY, HandshakeRejectReason.fromCode(0x04))
        assertEquals(HandshakeRejectReason.WINDOW_CLOSED, HandshakeRejectReason.fromCode(0x08))
        assertEquals(HandshakeRejectReason.AUTH_FAILED, HandshakeRejectReason.fromCode(0x00))
        assertEquals(HandshakeRejectReason.AUTH_FAILED, HandshakeRejectReason.fromCode(0x09))
        assertEquals(HandshakeRejectReason.AUTH_FAILED, HandshakeRejectReason.fromCode(0xff))
    }

    @Test
    fun commandPlaintexts() {
        assertEquals("01", Commands.openPayload(true).toHex())
        assertEquals("00", Commands.openPayload(false).toHex())
        assertEquals(0, Commands.CLOSE_PAYLOAD.size)
        assertEquals(0, Commands.PING_PAYLOAD.size)
    }
}
