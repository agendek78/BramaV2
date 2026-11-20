package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.HandshakeRejectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkConnectTest {

    @Test
    fun connectReachesReadyWithEventsInOrder() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        assertEquals(listOf("Scanning", "Connecting", "SecuringLink", "Authenticating", "Ready"), h.namesOf(id))
    }

    @Test
    fun secondConnectUsesRememberedAddressWithoutScan() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        val id2 = h.connect()
        h.settle()
        assertEquals("Connecting", h.namesOf(id2).first())
        assertEquals("Ready", h.namesOf(id2).last())
        assertEquals(1, h.radio.calls.count { it == "scan" })
    }

    @Test
    fun foreignDeviceIsSkipped() = runTest {
        val h = LinkHarness(this)
        h.radio.devicesInScan = listOf("FOREIGN" to info(ByteArray(16) { 0x7f }), SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId))
        val id = h.connect()
        h.settle()
        assertEquals("Ready", h.namesOf(id).last())
        assertTrue("connect:FOREIGN" in h.radio.calls)
        assertTrue("disconnect" in h.radio.calls)
    }

    @Test
    fun nothingFoundIn10sIsDisconnected() = runTest {
        val h = LinkHarness(this)
        h.radio.scanFindsNothing = true
        val id = h.connect()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(listOf("Scanning"), h.namesOf(id))
        h.settle(2)
        assertEquals(listOf("Scanning", "Disconnected"), h.namesOf(id))
        assertTrue("stopScan" in h.radio.calls)
    }

    @Test
    fun notBondedConnectReportsBondMissingWithoutBonding() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        val id = h.connect()
        h.settle()
        assertEquals("BondMissing", h.namesOf(id).last()) // naprawa bondu (9.5) — decyzja użytkownika
        assertTrue("createBond" !in h.radio.calls)
        // zapis CCCD bez bondu wywołałby na Androidzie systemowe parowanie — nie może nastąpić
        assertTrue("enableIndications" !in h.radio.calls)
    }

    @Test
    fun mtuFailureFallsBackTo23() = runTest {
        val h = LinkHarness(this)
        h.radio.negotiatedMtu = 23
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id).last())
    }

    @Test
    fun disconnectEmitsNothingFurther() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        val before = h.eventsOf(id).size
        h.link.disconnect()
        h.radio.inject(RadioEvent.Disconnected(RadioHandle(1)))
        h.settle()
        assertEquals(before, h.eventsOf(id).size)
        assertTrue("disconnect" in h.radio.calls)
    }

    @Test
    fun disconnectDuringScanStopsSilently() = runTest {
        val h = LinkHarness(this)
        h.radio.scanFindsNothing = true
        val id = h.connect()
        h.settle(1_000)
        h.link.disconnect()
        h.settle()
        assertEquals(listOf("Scanning"), h.namesOf(id))
        assertTrue("stopScan" in h.radio.calls)
    }

    @Test
    fun staleRadioHandleEventsAreIgnored() = runTest {
        val h = LinkHarness(this)
        val id1 = h.connect()
        h.settle()
        val id2 = h.connect() // zamyka pierwsze połączenie (uchwyt 1)
        h.radio.inject(RadioEvent.Disconnected(RadioHandle(1))) // w trakcie zestawiania nowego
        h.settle()
        h.radio.inject(RadioEvent.Disconnected(RadioHandle(1))) // i po READY nowego
        h.settle()
        assertEquals("Ready", h.namesOf(id1).last())
        assertEquals(listOf("Connecting", "SecuringLink", "Authenticating", "Ready"), h.namesOf(id2))
    }

    @Test
    fun newConnectionGetsNewId() = runTest {
        val h = LinkHarness(this)
        assertNotEquals(h.connect(), h.connect())
    }

    @Test
    fun rejectedBeginBeforeReadyIsReportedAndDisconnected() = runTest {
        val h = LinkHarness(this)
        h.gate.rejectNextBegin = HandshakeRejectReason.WINDOW_CLOSED
        val id = h.connect()
        h.settle()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED), h.eventsOf(id).last())
        assertTrue("disconnect" in h.radio.calls)
    }

    @Test
    fun callerMayZeroKeyRightAfterConnect() = runTest {
        val h = LinkHarness(this)
        val key = h.clientKey.copyOf()
        val id = h.link.connect(h.gate.deviceId, h.clientId, key)
        key.fill(0)
        h.settle()
        assertEquals("Ready", h.namesOf(id).last())
    }
}
