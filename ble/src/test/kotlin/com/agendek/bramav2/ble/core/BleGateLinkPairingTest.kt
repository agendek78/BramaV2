package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.ProvisioningQr
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkPairingTest {

    private val newClientId = ByteArray(16) { 0x66 }

    private fun LinkHarness.qr() = PairingSecret.Qr(ProvisioningQr(gate.deviceId.copyOf(), gate.setupKey.copyOf(), "482915".toByteArray(Charsets.US_ASCII)))

    /** `pair` aż do GRANT. */
    private fun LinkHarness.granted(): Long {
        val id = link.pair(qr(), newClientId)
        settle()
        return id
    }

    @Test
    fun pairCreatesBondAndGrantsKey() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        val id = h.granted()
        assertTrue("createBond" in h.radio.calls)
        val grant = h.eventsOf(id).filterIsInstance<LinkEvent.GrantReceived>().single()
        assertArrayEquals(h.gate.issuedClientKey, grant.clientKey)
    }

    @Test
    fun commitYieldsPairingDoneAsLastEvent() = runTest {
        val h = LinkHarness(this)
        val id = h.granted()
        h.link.commitPairing()
        h.settle()
        assertEquals(LinkEvent.PairingDone, h.eventsOf(id).last())
        assertTrue(newClientId.toHex() in h.gate.clients)
        assertTrue("disconnect" in h.radio.calls)
        val count = h.eventsOf(id).size
        h.radio.inject(RadioEvent.Disconnected(RadioHandle(1)))
        h.settle()
        assertEquals(count, h.eventsOf(id).size)
    }

    @Test
    fun abortAfterGrantEmitsNothing() = runTest {
        val h = LinkHarness(this)
        val id = h.granted()
        h.link.abortPairing()
        h.settle(200_000)
        assertTrue(h.eventsOf(id).last() is LinkEvent.GrantReceived)
        assertTrue("disconnect" in h.radio.calls)
    }

    @Test
    fun lostDoneEndsWithDisconnected() = runTest {
        val h = LinkHarness(this)
        val id = h.granted()
        h.radio.dropIndications = 3
        h.link.commitPairing()
        h.settle(200_000)
        assertEquals(LinkEvent.Disconnected, h.eventsOf(id).last())
        assertTrue(h.eventsOf(id).none { it == LinkEvent.HandshakeFailed })
    }

    @Test
    fun attemptExpiresBeforeCommitEndsWithDisconnected() = runTest {
        val h = LinkHarness(this)
        val id = h.granted()
        h.settle(200_000) // brak commitPairing() do T_attempt
        assertEquals(LinkEvent.Disconnected, h.eventsOf(id).last())
    }

    @Test
    fun rejectBeforeGrantIsReported() = runTest {
        val h = LinkHarness(this)
        h.gate.rejectNextBegin = HandshakeRejectReason.WINDOW_CLOSED
        val id = h.link.pair(h.qr(), newClientId)
        h.settle()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED), h.eventsOf(id).last())
    }

    @Test
    fun setupKeyCopyOutlivesCallerZeroing() = runTest {
        val h = LinkHarness(this)
        val qr = h.qr()
        val id = h.link.pair(qr, newClientId)
        qr.wipe() // :domain zeruje sekret po zakończeniu próby
        h.settle()
        assertTrue(h.eventsOf(id).any { it is LinkEvent.GrantReceived })
    }

    // --- bond systemowy po nieudanej rejestracji (16.11.2026) ---

    private fun LinkHarness.removedBonds() = radio.calls.filter { it.startsWith("removeBond:") }

    @Test
    fun successfulPairingKeepsSystemBond() = runTest {
        val h = LinkHarness(this)
        h.granted()
        h.link.commitPairing()
        h.settle()
        assertTrue(h.removedBonds().isEmpty())
    }

    @Test
    fun abortBeforeCommitRemovesSystemBond() = runTest {
        val h = LinkHarness(this)
        h.granted()
        h.link.abortPairing()
        h.settle(200_000)
        assertEquals(1, h.removedBonds().size)
    }

    @Test
    fun attemptExpiringBeforeCommitRemovesSystemBond() = runTest {
        val h = LinkHarness(this)
        h.granted()
        h.settle(200_000)
        assertEquals(1, h.removedBonds().size)
    }

    @Test
    fun rejectBeforeGrantRemovesSystemBond() = runTest {
        val h = LinkHarness(this)
        h.gate.rejectNextBegin = HandshakeRejectReason.WINDOW_CLOSED
        h.link.pair(h.qr(), newClientId)
        h.settle()
        assertEquals(1, h.removedBonds().size)
    }

    @Test
    fun lostDoneAfterCommitKeepsSystemBond() = runTest {
        val h = LinkHarness(this)
        h.granted()
        h.radio.dropIndications = 3
        h.link.commitPairing()
        h.settle(200_000)
        assertTrue("sterownik mógł zatwierdzić klienta: bond zostaje", h.removedBonds().isEmpty())
    }
}
