package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.PairingSecret
import com.agendek.bramav2.protocol.ProvisioningQr
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ustalenia końcowego przeglądu `:ble`. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkFinalReviewTest {

    @Test
    fun linkDropAfterInfoVerifiedEndsAttemptOnce() = runTest {
        val h = LinkHarness(this)
        h.radio.dropOnEnableIndications = true // np. nieaktualny bond: łącze pada po weryfikacji INFO
        val id = h.connect()
        h.settle(120_000)
        assertEquals(1, h.eventsOf(id).count { it == LinkEvent.Disconnected })
        assertEquals("Disconnected", h.namesOf(id).last())
        assertEquals(1, h.radio.calls.count { it == "scan" })
    }

    @Test
    fun rememberedAddressFailingAfterVerificationDoesNotRescan() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        h.radio.dropOnEnableIndications = true
        val id2 = h.connect()
        h.settle(120_000)
        assertEquals(listOf("Connecting", "SecuringLink", "Disconnected"), h.namesOf(id2))
        assertEquals(1, h.radio.calls.count { it == "scan" })
    }

    @Test
    fun gattTimeoutIsPerOperationNotPerBurst() = runTest {
        val h = LinkHarness(this)
        h.radio.opDelays["discover"] = 6_000 // < 10 s dla Discover
        h.radio.opDelays["readInfo"] = 4_500 // < 5 s dla ReadInfo, razem > 10 s
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id).last())
    }

    @Test
    fun writeFailureOnOwnerLinkRekeysWithoutDroppingLink() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        h.radio.failWrites = true
        h.link.send(SessionRequest.Close)
        h.settle(5_000)
        h.radio.failWrites = false
        h.settle(120_000)
        val names = h.namesOf(id)
        assertTrue(LinkEvent.CommandUnknown(SessionRequest.Close) in h.eventsOf(id))
        assertTrue("Rekeying" in names)
        assertEquals("Ready", names.last())
        assertTrue("disconnect" !in h.radio.calls)
        assertTrue(LinkEvent.Disconnected !in h.eventsOf(id))
    }

    @Test
    fun repeatedScanResultsAndUnreachableNeighbourStillConnect() = runTest {
        val h = LinkHarness(this)
        h.radio.scanRepeats = 3
        h.radio.devicesInScan = listOf(
            "NEIGHBOUR" to info(ByteArray(16) { 0x7e }), // sterownik sąsiada: filtr połączeń, connect wisi
            "FOREIGN" to info(ByteArray(16) { 0x7f }),
            SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId),
        )
        h.radio.neverConnects += "NEIGHBOUR"
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id).last())
        assertEquals(1, h.radio.calls.count { it == "connect:FOREIGN" })
        assertEquals(1, h.radio.calls.count { it == "connect:NEIGHBOUR" })
    }

    @Test
    fun protocolExceptionDoesNotSilenceActor() = runTest {
        val h = LinkHarness(this)
        val pairId = h.link.pair(PairingSecret.Qr(ProvisioningQr(h.gate.deviceId.copyOf(), h.gate.setupKey.copyOf(), "482915".toByteArray(Charsets.US_ASCII))), ByteArray(16) { 0x66 })
        h.settle()
        h.link.commitPairing()
        h.link.commitPairing() // drugi commit: PairingHandshake.commit() rzuca (zły stan)
        h.settle()
        assertTrue(h.eventsOf(pairId).isNotEmpty())
        val id = h.connect()
        h.settle()
        assertEquals("Ready", h.namesOf(id).last()) // aktor nadal działa
    }
}
