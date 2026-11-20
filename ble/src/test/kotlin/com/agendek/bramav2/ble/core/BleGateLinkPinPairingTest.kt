package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.ProvisioningQr
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rejestracja PIN-em (spec 11.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkPinPairingTest {

    private val newClientId = ByteArray(16) { 0x66 }
    private fun pin(text: String = "482915") = PairingSecret.Pin.parse(text)!!

    private fun LinkHarness.pairPin(text: String = "482915"): Long = link.pair(pin(text), newClientId).also { settle() }

    @Test
    fun pinPairingReachesGrantAndDone() = runTest {
        val h = LinkHarness(this)
        h.radio.bonded = false
        val id = h.pairPin()
        val grant = h.eventsOf(id).filterIsInstance<LinkEvent.GrantReceived>().single()
        assertArrayEquals(h.gate.issuedClientKey, grant.clientKey)
        assertArrayEquals(h.gate.deviceId, grant.deviceId)
        assertTrue(MessageType.PAKE_ROUND1 in h.gate.received && MessageType.PAKE_ROUND2 in h.gate.received)
        h.link.commitPairing()
        h.settle()
        assertEquals(LinkEvent.PairingDone, h.eventsOf(id).last())
        assertTrue(newClientId.toHex() in h.gate.clients)
    }

    @Test
    fun pinPairingSkipsCandidateWithoutPinCapability() = runTest {
        val h = LinkHarness(this)
        h.radio.devicesInScan = listOf(
            "OLD" to info(ByteArray(16) { 0x55 }, capabilities = 0x1F),
            SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId),
        )
        val id = h.pairPin()
        assertTrue(h.eventsOf(id).any { it is LinkEvent.GrantReceived })
        assertTrue("connect:OLD" in h.radio.calls)
    }

    @Test
    fun wrongPinEndsAttemptWithAuthFailedHint() = runTest {
        val h = LinkHarness(this)
        h.gate.pin = "111111".toByteArray(Charsets.US_ASCII)
        val id = h.pairPin()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED), h.eventsOf(id).last())
        assertTrue(h.eventsOf(id).none { it is LinkEvent.GrantReceived })
    }

    @Test
    fun lostPakeReplyIsRetransmitted() = runTest {
        val h = LinkHarness(this)
        h.radio.dropIndications = 1 // PAKE_ROUND1_REPLY ginie
        val id = h.link.pair(pin(), newClientId)
        h.settle(120_000)
        assertTrue(h.eventsOf(id).any { it is LinkEvent.GrantReceived })
        assertEquals(2, h.gate.received.count { it == MessageType.PAKE_ROUND1 })
    }

    @Test
    fun pakeRejectEndsAttempt() = runTest {
        val h = LinkHarness(this)
        h.gate.windowOpen = false
        val id = h.pairPin()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED), h.eventsOf(id).last())
        assertTrue(MessageType.PAIR_BEGIN !in h.gate.received)
    }

    @Test
    fun fakeGateWithoutPinIsRejected() = runTest {
        val h = LinkHarness(this)
        h.gate.pin = "999999".toByteArray(Charsets.US_ASCII)
        h.gate.forgeChallenge = true
        val id = h.pairPin()
        assertEquals(LinkEvent.HandshakeFailed, h.eventsOf(id).last())
        assertTrue(h.eventsOf(id).none { it is LinkEvent.GrantReceived })
        val scans = h.radio.calls.count { it == "scan" }
        h.link.connect(h.gate.deviceId, newClientId, ByteArray(32) { 1 })
        h.settle()
        assertEquals(scans + 1, h.radio.calls.count { it == "scan" }) // adres niezapamiętany
    }

    @Test
    fun rememberedAddressOnlyAfterChallenge() = runTest {
        val h = LinkHarness(this)
        h.gate.pin = "111111".toByteArray(Charsets.US_ASCII)
        h.pairPin()
        h.gate.pin = "482915".toByteArray(Charsets.US_ASCII)
        val scansBefore = h.radio.calls.count { it == "scan" }
        h.pairPin()
        h.link.commitPairing()
        h.settle()
        assertEquals(scansBefore + 1, h.radio.calls.count { it == "scan" }) // PIN zawsze skanuje
        h.link.connect(h.gate.deviceId, newClientId, h.gate.issuedClientKey)
        h.settle()
        assertEquals(scansBefore + 1, h.radio.calls.count { it == "scan" }) // po poprawnym CHALLENGE adres znany
    }

    @Test
    fun fifthWrongPinClosesWindow() = runTest {
        val h = LinkHarness(this)
        h.gate.pin = "111111".toByteArray(Charsets.US_ASCII)
        repeat(5) { h.pairPin() }
        assertEquals(5, h.gate.pinFailures)
        h.gate.pin = "482915".toByteArray(Charsets.US_ASCII)
        val id = h.pairPin()
        assertEquals(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED), h.eventsOf(id).last())
        assertTrue(h.eventsOf(id).none { it is LinkEvent.GrantReceived })
    }

    @Test
    fun grantReceivedCarriesDeviceIdForQr() = runTest {
        val h = LinkHarness(this)
        val qr = ProvisioningQr(h.gate.deviceId.copyOf(), h.gate.setupKey.copyOf(), "482915".toByteArray(Charsets.US_ASCII))
        val id = h.link.pair(PairingSecret.Qr(qr), newClientId)
        h.settle()
        assertArrayEquals(h.gate.deviceId, h.eventsOf(id).filterIsInstance<LinkEvent.GrantReceived>().single().deviceId)
        assertTrue(MessageType.PAKE_ROUND1 !in h.gate.received)
    }

    @Test
    fun wrongPinRemovesSystemBond() = runTest {
        val h = LinkHarness(this)
        h.pairPin("111111") // zły PIN
        assertTrue(h.radio.calls.any { it.startsWith("removeBond:") })
    }
}
