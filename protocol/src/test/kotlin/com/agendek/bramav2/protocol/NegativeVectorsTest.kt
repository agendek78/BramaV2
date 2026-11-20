package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Przypadki negatywne wektorów zgodności (16.3, vectors_design §3) po stronie telefonu: ramki s2c, snapshoty, ACK
 * i fragmenty sprawdzane na `ClientSession`, `Reassembler` i dekoderach; przypadki c2s — że są tym, co opisują.
 * Te same pliki odtwarza firmware (`firmware/test/host/main/test_negative.c`).
 */
class NegativeVectorsTest {

    private fun load(name: String): Map<String, ByteArray> =
        NegativeVectorsTest::class.java.getResource("/vectors/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=').hexToBytes() }

    private fun Map<String, ByteArray>.v(key: String): ByteArray = this[key] ?: error("Brak klucza $key")

    private fun keys(v: Map<String, ByteArray>) = SessionKeys(v.v("c2s_key"), v.v("s2c_key"), v.v("c2s_iv"), v.v("s2c_iv"))

    /** Sesja telefonu odtworzona z wymiany bazowej pliku (te same nonce co generator). */
    private fun session(v: Map<String, ByteArray>): ClientSession {
        val hs = SessionHandshake(v.v("k_client"), v.v("device_id"), v.v("client_id"), CountingNonceSource(1))
        assertArrayEquals(v.v("c2s_1"), hs.pendingRequest)
        val confirm = (hs.onFrame(v.v("s2c_1")) as HandshakeStep.Send).frame
        assertArrayEquals(v.v("c2s_2"), confirm)
        return (hs.onFrame(v.v("s2c_2")) as HandshakeStep.Established).session
    }

    private fun expect(v: Map<String, ByteArray>, case: String): Int = v.v("${case}_expect")[0].toInt()

    // --- negative_session.txt: wejście sterownika ---

    @Test
    fun sessionCorruptionsFailAuthentication() {
        val v = load("negative_session.txt")
        val k = keys(v)
        for (case in listOf("header_bit", "payload_bit", "tag_bit")) {
            assertEquals(case, VectorScenarios.EXPECT_INVALIDATED, expect(v, case))
            assertNull(case, GcmFrames.open(k.c2sKey, k.c2sIv, Frame.decode(v.v("${case}_frame"))))
        }
    }

    @Test
    fun sessionOrderingCases() {
        val v = load("negative_session.txt")
        val k = keys(v)
        val sid = Frame.decode(v.v("s2c_1")).header.sessionId
        val gap = Frame.decode(v.v("seq_gap_frame"))
        assertEquals(2L, gap.header.seq)
        assertTrue(GcmFrames.open(k.c2sKey, k.c2sIv, gap) != null) // poprawna kryptografia, zły licznik
        val first = Frame.decode(v.v("same_seq_changed_first"))
        val changed = Frame.decode(v.v("same_seq_changed_frame"))
        assertEquals(first.header.seq, changed.header.seq)
        assertFalse(v.v("same_seq_changed_first").contentEquals(v.v("same_seq_changed_frame")))
        assertTrue(GcmFrames.open(k.c2sKey, k.c2sIv, changed) != null)
        val prev = Frame.decode(v.v("prev_session_frame"))
        assertTrue(prev.header.sessionId != sid)
        assertNull(GcmFrames.open(k.c2sKey, k.c2sIv, prev))
        assertEquals(VectorScenarios.EXPECT_IGNORED, expect(v, "prev_session"))
    }

    @Test
    fun sessionFormatAndDirectionCases() {
        val v = load("negative_session.txt")
        assertThrows(ProtocolException::class.java) { Frame.decode(v.v("version1_frame")) }
        assertEquals(Direction.S2C, Frame.decode(v.v("wrong_direction_frame")).header.messageType!!.direction)
        assertEquals(MessageType.HANDSHAKE_REJECT, Frame.decode(v.v("reject_via_rx_frame")).header.messageType)
        for (case in listOf("version1", "wrong_direction", "reject_via_rx")) {
            assertEquals(case, VectorScenarios.EXPECT_INVALIDATED, expect(v, case))
        }
    }

    @Test
    fun malformedCommandsReachActorWithExpectedAck() {
        val v = load("negative_session.txt")
        val k = keys(v)
        val results = mapOf("open_bool_02" to AckResult.INVALID_ARGUMENT, "close_extra_byte" to AckResult.INVALID_ARGUMENT, "unknown_type" to AckResult.UNSUPPORTED_TYPE)
        for ((case, result) in results) {
            assertEquals(case, VectorScenarios.EXPECT_COMMAND, expect(v, case))
            val frame = Frame.decode(v.v("${case}_frame"))
            assertEquals(v.v("${case}_type")[0].toInt() and 0xff, frame.header.typeCode)
            assertArrayEquals(case, v.v("${case}_plain"), GcmFrames.open(k.c2sKey, k.c2sIv, frame))
            val ackPlain = GcmFrames.open(k.s2cKey, k.s2cIv, Frame.decode(v.v("${case}_ack")))
            assertArrayEquals(case, v.v("${case}_ack_plain"), ackPlain)
            assertEquals(case, result, CommandAck.decode(ackPlain!!).result)
        }
    }

    @Test
    fun rekeyOnKeptLinkGivesNewSession() {
        val v = load("negative_session.txt")
        val old = session(v)
        val hs = SessionHandshake(v.v("k_client"), v.v("device_id"), v.v("client_id"), CountingNonceSource(0x09))
        assertArrayEquals(v.v("rekey_begin"), hs.pendingRequest)
        val challenge = v.v("rekey_challenge")
        assertEquals(v.v("rekey_session_id").getLe(0, 8), Frame.decode(challenge).header.sessionId)
        assertArrayEquals(v.v("rekey_confirm"), (hs.onFrame(challenge) as HandshakeStep.Send).frame)
        val fresh = (hs.onFrame(v.v("rekey_ready")) as HandshakeStep.Established).session
        assertTrue(fresh.sessionId != old.sessionId)
        // Stara sesja ignoruje ramki nowej (inny session_id, 12.1).
        assertTrue(old.onFrame(v.v("rekey_ready")) is SessionEvent.Ignored)
    }

    // --- negative_handshake.txt ---

    @Test
    fun handshakeCasesExpectAuthFailed() {
        val v = load("negative_handshake.txt")
        for (case in listOf("begin_wrong_device_id", "begin_unknown_client", "begin_wrong_key", "confirm_without_begin", "reflection", "pake_bad_zkp", "pake_outside_window")) {
            assertEquals(case, VectorScenarios.EXPECT_REPLY, expect(v, case))
            val reply = Frame.decode(v.v("${case}_reply"))
            assertEquals(case, MessageType.HANDSHAKE_REJECT, reply.header.messageType)
            assertEquals(case, HandshakeRejectReason.AUTH_FAILED.code, reply.body[0].toInt())
            // Odmowa powtarza session_id odrzucanej ramki (12.4).
            assertEquals(case, Frame.decode(v.v("${case}_frame")).header.sessionId, reply.header.sessionId)
        }
        assertEquals(Direction.S2C, Frame.decode(v.v("reflection_frame")).header.messageType!!.direction)
    }

    @Test
    fun badZkpIsRejectedByReferenceServer() {
        val v = load("negative_handshake.txt")
        val server = EcJpake(JpakeRole.SERVER, v.v("pin"))
        assertThrows(ProtocolException::class.java) { server.readRound1(Frame.decode(v.v("pake_bad_zkp_frame")).body) }
        EcJpake(JpakeRole.SERVER, v.v("pin")).readRound1(Frame.decode(v.v("pake_outside_window_frame")).body) // poprawna runda
    }

    // --- negative_client.txt: wejście telefonu ---

    @Test
    fun ruleStatusBeforeAckThenStaleAckDuplicate() {
        val v = load("negative_client.txt")
        val s = session(v)
        assertArrayEquals(v.v("status_before_ack_c2s"), s.send(SessionRequest.Open(true)))
        val status = s.onFrame(v.v("status_before_ack_status")) as SessionEvent.RuleStatus
        assertTrue(status.applied)
        val ack = s.onFrame(v.v("status_before_ack_ack")) as SessionEvent.CommandAcknowledged
        assertEquals(AckResult.OUTPUT_TRIGGERED, ack.ack.result)
        assertFalse(ack.snapshotApplied) // ten sam revision co status
        assertTrue((s.onFrame(v.v("old_ack_after_status_status")) as SessionEvent.RuleStatus).applied)
        assertEquals(SessionEvent.Duplicate, s.onFrame(v.v("status_before_ack_ack")))
        assertEquals(AutoClosePhase.DISARMED, s.latestSnapshot.phase) // stan nie cofa się do starego revision
        assertEquals(3L, s.latestSnapshot.revision)
    }

    @Test
    fun badAcksInvalidateSession() {
        val v = load("negative_client.txt")
        for (case in listOf("ack_bad_tag", "ack_wrong_ref")) {
            val s = session(v)
            assertArrayEquals(v.v("ack_c2s"), s.send(SessionRequest.Close))
            val ev = s.onFrame(v.v("${case}_frame")) as SessionEvent.Failed
            assertEquals(case, SessionRequest.Close, ev.unresolved)
            assertFalse(s.isValid)
        }
    }

    @Test
    fun rejectHints() {
        val v = load("negative_client.txt")
        val hs = SessionHandshake(v.v("k_client"), v.v("device_id"), v.v("client_id"), CountingNonceSource(1))
        assertTrue(hs.onFrame(v.v("reject_other_sid_frame")) is HandshakeStep.Ignored)
        assertEquals(HandshakeStep.Rejected(HandshakeRejectReason.AUTH_FAILED), hs.onFrame(v.v("reject_unknown_code_frame")))
    }

    @Test
    fun everyValidSnapshotDecodesAndInvalidAreRejected() {
        val v = load("negative_client.txt")
        val valid = v.filterKeys { it.startsWith("snapshot_") && !it.startsWith("snapshot_invalid_") }
        assertEquals(16, valid.size) // 12.2: DISARMED 2, MONITORING/WAITING/BOOT_WAIT/BLOCKED po 2, FAULT 6
        for ((key, bytes) in valid) {
            val s = RuleSnapshot.decode(bytes)
            assertEquals(key, "snapshot_${s.phase.name.lowercase()}_${s.ownerRelation.name.lowercase()}_${s.reason.name.lowercase()}", key)
            assertArrayEquals(key, bytes, s.encode())
            assertEquals(0x0102030405060708L, s.revision)
        }
        val invalid = v.filterKeys { it.startsWith("snapshot_invalid_") }
        assertEquals(7, invalid.size)
        for ((key, bytes) in invalid) assertThrows(key, ProtocolException::class.java) { RuleSnapshot.decode(bytes) }
    }

    @Test
    fun everyAckResultDecodes() {
        val v = load("negative_client.txt")
        for (result in AckResult.entries) {
            val bytes = v.v("ack_${result.name.lowercase()}")
            val ack = CommandAck.decode(bytes)
            assertEquals(result, ack.result)
            assertEquals(0x1112131415161718L, ack.requestSeq)
            assertArrayEquals(bytes, ack.encode())
        }
    }

    @Test
    fun clientListVectorsDecodeOrAreRejected() {
        val v = load("negative_client.txt")
        val one = ClientList.decode(v.v("client_list_1"))
        assertEquals(7L, one.requestSeq)
        assertEquals(1, one.entries.size)
        val eight = ClientList.decode(v.v("client_list_8"))
        assertEquals(8, eight.entries.size)
        assertEquals("a".repeat(20), eight.entries[0].name)
        assertEquals("Zażółć", eight.entries[1].name)
        assertEquals(listOf(true, false, true, false, true, false, true, false), eight.entries.map { it.isConnected })
        assertEquals(3, eight.entries.indexOfFirst { it.ownsRule })
        assertArrayEquals(v.v("client_list_8"), eight.encode())
        for (bad in listOf("client_list_count_over_8", "client_list_bad_len", "client_list_bad_utf8", "client_list_name_len_21")) {
            assertThrows(bad, ProtocolException::class.java) { ClientList.decode(v.v(bad)) }
        }
    }

    // --- negative_fragments.txt ---

    @Test
    fun fragmentRestartRules() {
        val v = load("negative_fragments.txt")
        val budget = TimingBudget(23, 30, 0)
        val newTid = Reassembler { budget }
        val results = (0..3).map { newTid.accept(v.v("restart_new_tid_in_$it"), 1_000L + it) }
        assertArrayEquals(v.v("frame"), (results.last() as ReassemblyResult.Complete).frame)
        assertTrue(results.dropLast(1).none { it is ReassemblyResult.Complete })

        val sameTid = Reassembler { budget }
        val same = (0..3).map { sameTid.accept(v.v("restart_same_tid_in_$it"), 1_000L + it) }
        assertTrue(same[1] is ReassemblyResult.Dropped)
        assertTrue(same.none { it is ReassemblyResult.Complete })
    }
}
