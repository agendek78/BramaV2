package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.HandshakeRejectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Naprawa bondu przy zachowanym `K_client` (spec 9.5, bond_repair_design §4.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkBondRepairTest {

    private fun LinkHarness.repair(): Long = link.repairBond(gate.deviceId, clientId, clientKey)

    @Test
    fun repairCreatesBondThenSessionWithClientKey() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        val id = h.repair()
        h.settle()
        assertEquals(listOf("Scanning", "Connecting", "SecuringLink", "Authenticating", "Ready"), h.namesOf(id))
        val calls = h.radio.calls
        assertTrue(calls.indexOf("readFirmwareRevision") < calls.indexOf("createBond"))
        assertTrue(calls.indexOf("createBond") < calls.indexOf("enableIndications"))
        assertEquals("0.1.0", (h.eventsOf(id).last() as LinkEvent.Ready).info.firmwareVersion)
    }

    @Test
    fun repairWithExistingBondSkipsBonding() = runTest {
        val h = LinkHarness(this)
        val id = h.repair()
        h.settle()
        assertEquals("Ready", h.namesOf(id).last())
        assertFalse("createBond" in h.radio.calls)
    }

    @Test
    fun refusedBondingIsBondFailed() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        h.radio.bondRefused = true
        val id = h.repair()
        h.settle()
        assertEquals("BondFailed", h.namesOf(id).last())
        assertFalse("enableIndications" in h.radio.calls)
        assertTrue("disconnect" in h.radio.calls)
        // Po BondFailed połączenie zakończone: brak dalszych zdarzeń.
        h.settle(120_000)
        assertEquals("BondFailed", h.namesOf(id).last())
    }

    @Test
    fun repairRejectionIsReportedLikeOrdinarySession() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        h.gate.rejectNextBegin = HandshakeRejectReason.WINDOW_CLOSED
        val id = h.repair()
        h.settle()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED), h.eventsOf(id).last())
    }

    @Test
    fun ordinaryConnectStillNeverBonds() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        val id = h.connect()
        h.settle()
        assertEquals(listOf("Scanning", "Connecting", "BondMissing"), h.namesOf(id))
        assertFalse("createBond" in h.radio.calls)
        assertTrue("disconnect" in h.radio.calls)
    }
}
