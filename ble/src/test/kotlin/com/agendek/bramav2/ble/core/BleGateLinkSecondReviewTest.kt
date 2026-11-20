package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.ble.sim.info
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ustalenia przeglądu `:ble` po poprawkach. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkSecondReviewTest {

    @Test
    fun nextCandidateDiscoverStillHasTimeoutAfterPreviousDroppedDuringDiscover() = runTest {
        val h = LinkHarness(this)
        h.radio.devicesInScan = listOf("FIRST" to info(h.gate.deviceId), SimulatedRadio.GATE_ADDRESS to info(h.gate.deviceId))
        h.radio.dropOnDiscover += "FIRST"
        h.radio.discoverNeverAnswers += SimulatedRadio.GATE_ADDRESS // Android „zawiesza” discoverServices
        val id = h.connect()
        h.settle(120_000)
        assertEquals("Disconnected", h.namesOf(id).last()) // brak zawieszenia próby
    }

    @Test
    fun keptLinkRetryDropsStaleFragmentsOfAbortedRequest() = runTest {
        val h = LinkHarness(this)
        h.radio.negotiatedMtu = 23 // BEGIN w 6 fragmentach
        val id = h.connect()
        h.settle(60_000)
        h.radio.dropIndications = 3
        h.link.send(SessionRequest.Ping) // trzy zgubione PONG → rekey na zachowanym łączu
        var guard = 0
        while ("Rekeying" !in h.namesOf(id) && guard++ < 400) h.settle(500)
        assertTrue("Rekeying" in h.namesOf(id))
        h.radio.opDelays["write"] = 1_000 // dalsze fragmenty nowego BEGIN wolno (~1 s każdy)
        h.settle(1_500)
        // śmieciowe wskazanie: handshake na zachowanym łączu kończy się HandshakeFailed w trakcie zapisu BEGIN
        h.radio.inject(RadioEvent.TxIndication(h.radio.currentHandle!!, byteArrayOf(-56, 0, 1) + ByteArray(17) { 1 }))
        h.radio.opDelays.remove("write")
        h.settle(300_000)
        assertTrue(LinkEvent.HandshakeFailed in h.eventsOf(id))
        assertEquals("Ready", h.namesOf(id).last())
        // przerwany BEGIN nie dotarł w całości do sterownika: tylko pierwszy i ponowiony
        assertEquals(2, h.gate.received.count { it == MessageType.SESSION_BEGIN })
    }

    @Test
    fun responseBeforeWriteCallbackDoesNotArmStaleTimer() = runTest {
        val h = LinkHarness(this)
        val id = h.connect()
        h.settle()
        h.radio.opDelays["write"] = 2_000 // callback zapisu po odpowiedzi
        h.link.send(SessionRequest.Close)
        h.settle(500)
        assertTrue(h.eventsOf(id).any { it is LinkEvent.CommandResult })
        h.radio.dropIndications = 1
        val pingAt = testScheduler.currentTime
        h.link.send(SessionRequest.Ping)
        h.settle(60_000)
        val pongIndex = h.messages.indexOfFirst { it.event is LinkEvent.Pong }
        // retransmisja dopiero po T_response liczonym od zakończenia zapisu PING, nie od starego zapisu CLOSE
        assertTrue(h.times[pongIndex] - pingAt >= 2_000 + 11_400)
    }

    @Test
    fun failedDiscoverDoesNotReadInfo() = runTest {
        val h = LinkHarness(this)
        h.radio.discoverFails = true
        val id = h.connect()
        h.settle(60_000)
        assertEquals("Disconnected", h.namesOf(id).last())
        assertTrue("readInfo" !in h.radio.calls)
    }

    @Test
    fun slowMultiFragmentWritesEachGetTheirOwnTimeout() = runTest {
        val h = LinkHarness(this)
        h.radio.negotiatedMtu = 23 // BEGIN w 6 fragmentach
        h.radio.opDelays["write"] = 3_000 // każdy zapis < 5 s, cała ramka > 5 s
        val id = h.connect()
        h.settle(600_000)
        assertEquals("Ready", h.namesOf(id).last())
    }
}
