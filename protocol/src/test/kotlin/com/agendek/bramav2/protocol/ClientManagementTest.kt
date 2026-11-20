package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Zarządzanie telefonami na sterowniku (client_management_design.md): typy 04–06, 82 i wyniki ACK 09–0D. */
class ClientManagementTest {

    private val gate = FakeGate()
    private val me = ByteArray(16) { 0x44 }
    private val other = ByteArray(16) { 0x45 }
    private val third = ByteArray(16) { 0x46 }

    init {
        for (id in listOf(me, other, third)) gate.clients[id.toHex()] = ByteArray(32) { 0x55 }
        gate.clientNames[other.toHex()] = "Ania"
        gate.connected += other.toHex()
    }

    private fun establish(): ClientSession {
        val hs = SessionHandshake(gate.clients[me.toHex()]!!, gate.deviceId, me, CountingNonceSource(1))
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        return (hs.onFrame(gate.receive(confirm)!!) as HandshakeStep.Established).session
    }

    private fun ClientSession.ack(request: SessionRequest): SessionEvent.CommandAcknowledged =
        onFrame(gate.receive(send(request))!!) as SessionEvent.CommandAcknowledged

    @Test
    fun codesAreFixed() {
        assertEquals(MessageType.LIST_CLIENTS, MessageType.fromCode(0x04))
        assertEquals(MessageType.REMOVE_CLIENT, MessageType.fromCode(0x05))
        assertEquals(MessageType.SET_CLIENT_NAME, MessageType.fromCode(0x06))
        assertEquals(MessageType.CLIENT_LIST, MessageType.fromCode(0x82))
        for (t in listOf(MessageType.LIST_CLIENTS, MessageType.REMOVE_CLIENT, MessageType.SET_CLIENT_NAME)) {
            assertEquals(Direction.C2S, t.direction)
            assertEquals(TagKind.GCM, t.tagKind)
            assertEquals(MessagePhase.SESSION, t.phase)
        }
        assertEquals(Direction.S2C, MessageType.CLIENT_LIST.direction)
        assertEquals(TagKind.GCM, MessageType.CLIENT_LIST.tagKind)
        assertEquals(
            listOf(9, 10, 11, 12, 13),
            listOf(AckResult.CLIENT_REMOVED, AckResult.NAME_SET, AckResult.CLIENT_NOT_FOUND, AckResult.CANNOT_REMOVE_SELF, AckResult.CLIENT_OWNS_RULE).map { it.code },
        )
    }

    @Test
    fun listRequestIsEmptyAndAnsweredWithEntries() {
        val s = establish()
        val req = s.send(SessionRequest.ListClients)
        assertEquals(38, req.size)
        assertEquals(0x04, Frame.decode(req).header.typeCode)
        val ev = s.onFrame(gate.receive(req)!!) as SessionEvent.ClientListReceived
        assertEquals(SessionRequest.ListClients, ev.request)
        assertEquals(1L, ev.list.requestSeq)
        assertEquals(3, ev.list.entries.size)
        val (a, b, c) = ev.list.entries
        assertArrayEquals(me, a.clientId); assertTrue(a.isSelf); assertTrue(a.isConnected); assertFalse(a.ownsRule); assertEquals("", a.name)
        assertArrayEquals(other, b.clientId); assertFalse(b.isSelf); assertTrue(b.isConnected); assertEquals("Ania", b.name)
        assertArrayEquals(third, c.clientId); assertFalse(c.isSelf); assertFalse(c.isConnected)
        assertNull(s.pendingRequest)
    }

    @Test
    fun ownerFlagIsReported() {
        gate.ruleOwner = other.toHex()
        val s = establish()
        val ev = s.onFrame(gate.receive(s.send(SessionRequest.ListClients))!!) as SessionEvent.ClientListReceived
        assertTrue(ev.list.entries[1].ownsRule)
        assertFalse(ev.list.entries[0].ownsRule)
    }

    @Test
    fun removeClientIsAcknowledgedAndRemovesIt() {
        val s = establish()
        val req = s.send(SessionRequest.RemoveClient(third))
        assertEquals(22 + 16 + 16, req.size)
        val ev = s.onFrame(gate.receive(req)!!) as SessionEvent.CommandAcknowledged
        assertEquals(AckResult.CLIENT_REMOVED, ev.ack.result)
        assertEquals(0x05, ev.ack.requestTypeCode)
        assertFalse(gate.clients.containsKey(third.toHex()))
        assertEquals(2, (s.onFrame(gate.receive(s.send(SessionRequest.ListClients))!!) as SessionEvent.ClientListReceived).list.entries.size)
    }

    @Test
    fun removeRefusalsAreReportedAndChangeNothing() {
        val s = establish()
        assertEquals(AckResult.CANNOT_REMOVE_SELF, s.ack(SessionRequest.RemoveClient(me)).ack.result)
        assertEquals(AckResult.CLIENT_NOT_FOUND, s.ack(SessionRequest.RemoveClient(ByteArray(16) { 0x77 })).ack.result)
        gate.ruleOwner = other.toHex()
        assertEquals(AckResult.CLIENT_OWNS_RULE, s.ack(SessionRequest.RemoveClient(other)).ack.result)
        assertEquals(3, gate.clients.size)
    }

    @Test
    fun removeClientNeedsSixteenByteId() {
        try {
            SessionRequest.RemoveClient(ByteArray(15))
            fail("oczekiwano IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun setNameIsAcknowledgedAndVisibleInList() {
        val s = establish()
        val req = s.send(SessionRequest.SetClientName("Pixel 8"))
        assertEquals(22 + 1 + 7 + 16, req.size)
        val ev = s.onFrame(gate.receive(req)!!) as SessionEvent.CommandAcknowledged
        assertEquals(AckResult.NAME_SET, ev.ack.result)
        val list = s.onFrame(gate.receive(s.send(SessionRequest.ListClients))!!) as SessionEvent.ClientListReceived
        assertEquals("Pixel 8", list.list.entries[0].name)
        assertEquals(AckResult.NAME_SET, s.ack(SessionRequest.SetClientName("")).ack.result) // pusta czyści
    }

    @Test
    fun clientNameRules() {
        assertTrue(ClientName.isValid(""))
        assertTrue(ClientName.isValid("a".repeat(20)))
        assertFalse(ClientName.isValid("a".repeat(21)))
        assertTrue(ClientName.isValid("Zażółć")) // 10 B UTF-8
        assertFalse(ClientName.isValid("a\u0001b"))
        assertFalse(ClientName.isValid("a\u007f"))
        assertFalse(ClientName.isValid("a\u0085"))
        assertFalse(ClientName.isValid("\uD800")) // niesparowany surogat
        assertEquals(20, ClientName.encode("a".repeat(20)).size)
        try {
            ClientName.encode("a".repeat(21))
            fail("oczekiwano IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun truncateCutsOnCharacterBoundaryAndRemovesControls() {
        assertEquals("Pixel 8", ClientName.truncate("Pixel 8"))
        assertEquals("a".repeat(20), ClientName.truncate("a".repeat(30)))
        // 'ż' ma 2 B: 19 × 'a' + 'ż' to 21 B, więc ż odpada
        assertEquals("a".repeat(19), ClientName.truncate("a".repeat(19) + "ż"))
        assertEquals("ab", ClientName.truncate("a\u0001b"))
        assertEquals("😀".repeat(5), ClientName.truncate("😀".repeat(6))) // 4 B na znak
        assertEquals("", ClientName.truncate("\uD800"))
        assertTrue(ClientName.isValid(ClientName.truncate("Telefon\u0000 \uD83D Ani".repeat(3))))
    }

    @Test
    fun clientListCodecRoundTrips() {
        val list = ClientList(
            9,
            listOf(
                ClientEntry(me, "Telefon A", isSelf = true, isConnected = true, ownsRule = false),
                ClientEntry(other, "", isSelf = false, isConnected = false, ownsRule = true),
            ),
        )
        val bytes = list.encode()
        assertEquals(8 + 1 + 2 * 38, bytes.size)
        assertEquals(list, ClientList.decode(bytes))
    }

    @Test
    fun clientListDecodeRejectsMalformedPayloads() {
        val ok = ClientList(1, listOf(ClientEntry(me, "x", isSelf = true, isConnected = true, ownsRule = false))).encode()
        fun bad(reason: String, bytes: ByteArray) = try {
            ClientList.decode(bytes)
            fail("zaakceptowano: $reason")
        } catch (_: ProtocolException) {
        }
        bad("za krótki", ok.copyOf(8))
        bad("rozmiar nie zgadza się z count", ok.copyOf(ok.size - 1))
        bad("count > 8", ByteArray(9 + 9 * 38).also { it[8] = 9 })
        bad("name_len > 20", ok.copyOf().also { it[8 + 1 + 17] = 21 })
        bad("niezerowe wypełnienie", ok.copyOf().also { it[8 + 1 + 18 + 5] = 1 })
        bad("zły UTF-8", ok.copyOf().also { it[8 + 1 + 18] = 0xC3.toByte() })
        bad("znak sterujący", ok.copyOf().also { it[8 + 1 + 18] = 0x01 })
        // nieznane bity flags są ignorowane
        val extraFlags = ok.copyOf().also { it[8 + 1 + 16] = (it[8 + 1 + 16].toInt() or 0xF8).toByte() }
        assertEquals(ClientList.decode(ok), ClientList.decode(extraFlags))
    }

    @Test
    fun clientListWithWrongRequestSeqInvalidatesSession() {
        val s = establish()
        s.send(SessionRequest.ListClients)
        val wrong = gate.sealRaw(MessageType.CLIENT_LIST, 2, ClientList(99, emptyList()).encode())
        val ev = s.onFrame(wrong)
        assertTrue(ev is SessionEvent.Failed)
        assertFalse(s.isValid)
    }

    @Test
    fun clientListWithoutPendingRequestInvalidatesSession() {
        val s = establish()
        val ev = s.onFrame(gate.sealRaw(MessageType.CLIENT_LIST, 2, ClientList(1, emptyList()).encode()))
        assertTrue(ev is SessionEvent.Failed)
    }

    @Test
    fun listAnsweredWithAckIsAcceptedAsCommandResult() {
        // firmware odpowiada ACK INVALID_ARGUMENT na niepoprawne żądanie listy; sesja przyjmuje ACK z referencją żądania
        val s = establish()
        s.send(SessionRequest.ListClients)
        val snap = RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        val ack = gate.sealRaw(MessageType.COMMAND_ACK, 2, CommandAck(0x04, AckResult.INVALID_ARGUMENT, 1, snap).encode())
        val ev = s.onFrame(ack) as SessionEvent.CommandAcknowledged
        assertEquals(AckResult.INVALID_ARGUMENT, ev.ack.result)
    }
}
