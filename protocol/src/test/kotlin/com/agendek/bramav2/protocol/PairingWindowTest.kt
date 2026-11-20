package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Okno rejestracji otwierane z telefonu (phone_window_design §3): typ 0x03, wynik ACK 0x08. */
class PairingWindowTest {

    private val gate = FakeGate()
    private val clientId = ByteArray(16) { 0x44 }
    private val clientKey = ByteArray(32) { 0x55 }.also { gate.clients[clientId.toHex()] = it }

    private fun establish(): ClientSession {
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId, CountingNonceSource(1))
        val confirm = (hs.onFrame(gate.receive(hs.pendingRequest!!)!!) as HandshakeStep.Send).frame
        return (hs.onFrame(gate.receive(confirm)!!) as HandshakeStep.Established).session
    }

    @Test
    fun codesAreFixed() {
        assertEquals(MessageType.OPEN_PAIRING_WINDOW, MessageType.fromCode(0x03))
        assertEquals(Direction.C2S, MessageType.OPEN_PAIRING_WINDOW.direction)
        assertEquals(MessagePhase.SESSION, MessageType.OPEN_PAIRING_WINDOW.phase)
        assertEquals(8, AckResult.WINDOW_OPENED.code)
    }

    @Test
    fun requestIsEmptyGcmCommandAcknowledgedWithWindowOpened() {
        val s = establish()
        val req = s.send(SessionRequest.OpenPairingWindow)
        assertEquals(38, req.size) // jak CLOSE: 22 B nagłówka + 0 B + 16 B tagu
        assertEquals(0x03, Frame.decode(req).header.typeCode)
        val ev = s.onFrame(gate.receive(req)!!) as SessionEvent.CommandAcknowledged
        assertEquals(SessionRequest.OpenPairingWindow, ev.request)
        assertEquals(AckResult.WINDOW_OPENED, ev.ack.result)
        assertEquals(0x03, ev.ack.requestTypeCode)
        assertNull(s.pendingRequest)
        assertTrue(gate.impulses.isEmpty()) // to nie jest komenda ruchu
        assertEquals(1, gate.windowsOpened)
    }

    @Test
    fun ackDecodesWindowOpened() {
        val snapshot = RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        val bytes = CommandAck(0x03, AckResult.WINDOW_OPENED, 7, snapshot).encode()
        assertEquals(AckResult.WINDOW_OPENED, CommandAck.decode(bytes).result)
    }
}
