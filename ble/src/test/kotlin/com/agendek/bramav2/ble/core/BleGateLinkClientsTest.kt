package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zarządzanie telefonami przez sesję (client_management_design.md §5.2): lista, usunięcie, nazwa. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkClientsTest {

    private val other = ByteArray(16) { 0x45 }

    private fun LinkHarness.withOther() {
        gate.clients[other.toHex()] = ByteArray(32) { 0x56 }
        gate.clientNames[other.toHex()] = "Ania"
    }

    @Test
    fun listIsDeliveredAsClientListEvent() = runTest {
        val h = LinkHarness(this)
        h.withOther()
        val id = h.connect()
        h.settle()
        h.link.send(SessionRequest.ListClients)
        h.settle(5_000)
        val event = h.eventsOf(id).filterIsInstance<LinkEvent.ClientList>().single()
        assertEquals(SessionRequest.ListClients, event.request)
        assertEquals(2, event.list.entries.size)
        assertTrue(event.list.entries[0].isSelf)
        assertArrayEquals(other, event.list.entries[1].clientId)
        assertEquals("Ania", event.list.entries[1].name)
        assertFalse(event.list.entries[1].isConnected)
    }

    @Test
    fun removeAndRenameAreAcknowledged() = runTest {
        val h = LinkHarness(this)
        h.withOther()
        val id = h.connect()
        h.settle()
        h.link.send(SessionRequest.SetClientName("Pixel 8"))
        h.settle(5_000)
        h.link.send(SessionRequest.RemoveClient(other))
        h.settle(5_000)
        val results = h.eventsOf(id).filterIsInstance<LinkEvent.CommandResult>().map { it.ack.result }
        assertEquals(listOf(AckResult.NAME_SET, AckResult.CLIENT_REMOVED), results)
        assertFalse(h.gate.clients.containsKey(other.toHex()))
        assertEquals("Pixel 8", h.gate.clientNames[h.clientId.toHex()])
    }

    @Test
    fun largeListSurvivesFragmentationAtSmallMtu() = runTest {
        val h = LinkHarness(this)
        h.radio.mtuNeverAnswers = true // MTU 23: lista 8 telefonów (351 B) idzie w 21 fragmentach
        for (i in 0 until 7) {
            val id = ByteArray(16) { (0x50 + i).toByte() }
            h.gate.clients[id.toHex()] = ByteArray(32) { 0x56 }
            h.gate.clientNames[id.toHex()] = "Telefon numer $i"
        }
        val id = h.connect()
        h.settle()
        h.link.send(SessionRequest.ListClients)
        h.settle(30_000)
        assertEquals(8, h.eventsOf(id).filterIsInstance<LinkEvent.ClientList>().single().list.entries.size)
    }
}
