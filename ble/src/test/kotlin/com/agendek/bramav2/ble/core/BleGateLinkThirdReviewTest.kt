package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ustalenia trzeciego przeglądu `:ble`. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkThirdReviewTest {

    @Test
    fun operationStartedByLateCallbackGetsFullTimeout() = runTest {
        val h = LinkHarness(this)
        h.radio.negotiatedMtu = 23 // SESSION_BEGIN w kilku fragmentach: sterownik nie odpowie przed końcem karencji
        val id = h.connect()
        h.settle()
        h.radio.opDelays["write"] = 9_000 // zapis PING przeterminowany po 5 s, callback w 4. s karencji
        h.link.send(SessionRequest.Ping)
        h.settle(1_000)
        h.radio.opDelays["write"] = 4_500 // kolejne zapisy mieszczą się w 5 s
        h.settle(120_000)
        val afterPing = h.eventsOf(id).dropWhile { it != LinkEvent.Rekeying }
        assertEquals(1, afterPing.count { it == LinkEvent.Rekeying })
        assertEquals("Ready", h.namesOf(id).last())
    }

    @Test
    fun rescanAfterTransientFailureOfRememberedAddressFindsSameAddress() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        h.radio.scanRepeats = 80 // Android raportuje reklamującego wielokrotnie (ponowienie po przerwie RETRY_PAUSE_MS)
        h.radio.neverConnects += SimulatedRadio.GATE_ADDRESS // chwilowo poza zasięgiem
        val id2 = h.connect()
        h.settle(1_000)
        h.radio.neverConnects -= SimulatedRadio.GATE_ADDRESS
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id2).last()) // adres statyczny (spec 8.3) — ten sam po skanie
    }

    @Test
    fun repeatedConnectedOnLiveLinkIsIgnored() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        val discovers = h.radio.calls.count { it == "discover" }
        h.radio.inject(RadioEvent.Connected(h.radio.currentHandle!!))
        h.settle(1_000)
        h.link.send(SessionRequest.Ping)
        h.settle(5_000)
        assertEquals(discovers, h.radio.calls.count { it == "discover" })
        assertTrue(h.eventsOf(id).last() is LinkEvent.Pong)
    }

    @Test
    fun foreignDeviceAtRememberedAddressIsNotRetriedByRescan() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        val gate = SimulatedRadio.GATE_ADDRESS
        h.radio.devicesInScan = listOf(gate to info(ByteArray(16) { 0x77 }), "NEW" to info(h.gate.deviceId))
        val id2 = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id2).last())
        assertEquals(2, h.radio.calls.count { it == "connect:$gate" }) // pierwsze połączenie + zapamiętany adres
    }

    @Test
    fun unansweredMtuGraceDoesNotEatHandshakeBudget() = runTest {
        val h = LinkHarness(this)
        h.radio.mtuNeverAnswers = true
        val id = h.connect()
        h.settle(60_000)
        assertEquals(listOf("Scanning", "Connecting", "SecuringLink", "Authenticating", "Ready"), h.namesOf(id))
    }

    /** Brama ze skanu chwilowo nie zestawia łącza (np. status 133), potem reklamuje się dalej. */
    private fun LinkHarness.transientlyUnreachableGate() {
        radio.scanRepeats = 80 // Android raportuje reklamującego co 100 ms przez całe okno skanu
        radio.neverConnects += SimulatedRadio.GATE_ADDRESS
        test.backgroundScope.launch {
            delay(1_000)
            radio.neverConnects -= SimulatedRadio.GATE_ADDRESS
        }
    }

    @Test
    fun scannedGateFailingTransientlyIsRetriedWithinScanWindow() = runTest {
        val h = LinkHarness(this)
        h.transientlyUnreachableGate()
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id).last())
    }

    @Test
    fun returningToScanAfterCandidateFailureAnnouncesScanning() = runTest {
        val h = LinkHarness(this)
        h.transientlyUnreachableGate()
        val id = h.connect()
        h.settle(60_000)
        assertEquals(listOf("Scanning", "Connecting", "Scanning", "Connecting"), h.namesOf(id).take(4))
    }

    @Test
    fun rememberedAddressSurvivesTransientFailure() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        h.radio.neverConnects += SimulatedRadio.GATE_ADDRESS // brama chwilowo wyłączona
        h.radio.scanFindsNothing = true
        val id2 = h.connect()
        h.settle(60_000)
        assertEquals("Disconnected", h.namesOf(id2).last())
        h.radio.neverConnects -= SimulatedRadio.GATE_ADDRESS
        h.radio.scanFindsNothing = false
        val scans = h.radio.calls.count { it == "scan" }
        val id3 = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id3).last())
        assertEquals(scans, h.radio.calls.count { it == "scan" }) // od razu zapamiętany adres, bez skanu
    }

    @Test
    fun transientlyFailingAddressIsTriedAtMostTwiceWithPause() = runTest {
        val h = LinkHarness(this)
        h.radio.scanRepeats = 80
        h.radio.failsImmediately += SimulatedRadio.GATE_ADDRESS
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Disconnected", h.namesOf(id).last())
        assertEquals(2, h.radio.calls.count { it == "connect:${SimulatedRadio.GATE_ADDRESS}" })
        val connecting = h.messages.indices.filter { h.messages[it].event == LinkEvent.Connecting }
        assertTrue(h.times[connecting[1]] - h.times[connecting[0]] >= 1_000)
    }

    @Test
    fun unparsableInfoAtRememberedAddressKeepsIt() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        val good = h.radio.devicesInScan
        h.radio.devicesInScan = listOf(SimulatedRadio.GATE_ADDRESS to (byteArrayOf(9) + info(h.gate.deviceId).copyOfRange(1, 20)))
        h.connect()
        h.settle(60_000)
        h.radio.devicesInScan = good
        val scans = h.radio.calls.count { it == "scan" }
        val id3 = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id3).last())
        assertEquals(scans, h.radio.calls.count { it == "scan" }) // nieznany format INFO to nie dowód obcego urządzenia
    }

    @Test
    fun foreignInfoAtRememberedAddressFoundByRescanForgetsIt() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        val gate = SimulatedRadio.GATE_ADDRESS
        h.radio.scanRepeats = 80
        h.radio.neverConnects += gate // pierwsza próba z pamięci: chwilowa porażka
        h.radio.devicesInScan = listOf(gate to info(ByteArray(16) { 0x77 })) // w skanie: obce urządzenie pod tym adresem
        backgroundScope.launch {
            delay(10_500)
            h.radio.neverConnects -= gate
        }
        h.connect()
        h.settle(60_000)
        h.radio.devicesInScan = listOf("NEW" to info(h.gate.deviceId))
        val before = h.radio.calls.size
        val id3 = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id3).last())
        assertEquals("scan", h.radio.calls[before]) // adres zapomniany: od razu skan
    }

    @Test
    fun rememberedAddressFoundByRescanGetsFullConnectTimeout() = runTest {
        val h = LinkHarness(this)
        h.connect()
        h.settle()
        h.link.disconnect()
        val gate = SimulatedRadio.GATE_ADDRESS
        h.radio.scanRepeats = 80
        h.radio.neverConnects += gate
        backgroundScope.launch {
            delay(9_000)
            h.radio.neverConnects -= gate
            h.radio.opDelays["connect"] = 7_000 // słaby sygnał: zestawienie trwa 7 s (< 10 s, > 5 s)
        }
        val id2 = h.connect()
        h.settle(60_000)
        assertEquals("Ready", h.namesOf(id2).last())
    }

    @Test
    fun neighbourWithOtherInfoVersionIsForeign() = runTest {
        val h = LinkHarness(this)
        h.radio.scanRepeats = 80
        val other = byteArrayOf(9) + info(ByteArray(16) { 0x77 }).copyOfRange(1, 20) // nowsza wersja, inny device_id
        h.radio.devicesInScan = listOf("NEIGHBOUR" to other)
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Disconnected", h.namesOf(id).last())
        assertEquals(1, h.radio.calls.count { it == "connect:NEIGHBOUR" })
    }
}
