package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.protocol.PairingSecret
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zapamiętany adres sterownika dla powiązania CDM (app_design §5). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkKnownAddressTest {

    @Test
    fun knownAddressAfterSessionReady() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        assertEquals(SimulatedRadio.GATE_ADDRESS, h.link.knownAddress(h.gate.deviceId))
    }

    @Test
    fun knownAddressNullBeforeAuthentication() = runTest {
        val h = LinkHarness(this)
        h.gate.windowOpen = false // PAKE odrzucone: PAIR_CHALLENGE nigdy nie przychodzi
        h.link.pair(PairingSecret.Pin.parse("482915")!!, ByteArray(16) { 0x66 })
        h.settle()
        assertNull(h.link.knownAddress(h.gate.deviceId))
    }

    @Test
    fun rememberedAddressSkipsScan() = runTest {
        val h = LinkHarness(this)
        h.link.rememberAddress(h.gate.deviceId, SimulatedRadio.GATE_ADDRESS)
        h.connect()
        h.settle()
        assertEquals(0, h.radio.calls.count { it == "scan" })
        assertEquals("Ready", h.namesOf(1).last())
    }
}
