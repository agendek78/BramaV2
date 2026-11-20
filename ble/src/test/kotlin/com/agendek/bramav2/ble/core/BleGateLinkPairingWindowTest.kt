package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Okno rejestracji z telefonu (phone_window_design §3, §5): bit 7 w `LinkInfo` i komenda przez sesję. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkPairingWindowTest {

    @Test
    fun readyReportsBit7AndCommandIsAcknowledged() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        assertTrue((h.eventsOf(id).last() as LinkEvent.Ready).info.pairingWindowSupported)
        h.link.send(SessionRequest.OpenPairingWindow)
        h.settle(5_000)
        val result = h.eventsOf(id).filterIsInstance<LinkEvent.CommandResult>().single()
        assertEquals(SessionRequest.OpenPairingWindow, result.request)
        assertEquals(AckResult.WINDOW_OPENED, result.ack.result)
        assertEquals(1, h.gate.windowsOpened)
        assertTrue(h.gate.impulses.isEmpty())
    }

    @Test
    fun olderFirmwareWithoutBit7() = runTest {
        val h = LinkHarness(this)
        h.radio.devicesInScan = listOf(SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId, capabilities = 0x7F))
        val id = h.connect()
        h.settle()
        assertFalse((h.eventsOf(id).last() as LinkEvent.Ready).info.pairingWindowSupported)
    }
}
