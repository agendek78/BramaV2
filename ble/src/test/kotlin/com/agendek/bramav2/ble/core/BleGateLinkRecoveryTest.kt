package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ble_design §4.3a: odtwarzanie sesji na łączu, które osiągnęło READY, bez zrywania BLE właściciela. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkRecoveryTest {

    /** READY, potem sesja unieważniona trzema zgubionymi odpowiedziami na PING → handshake na zachowanym łączu. */
    private fun LinkHarness.keptLinkHandshake(extraDrops: Int = 0, reject: HandshakeRejectReason? = null): Long {
        val id = connect()
        settle()
        gate.rejectNextBegin = reject // dotyczy handshake na zachowanym łączu, nie pierwszego połączenia
        radio.dropIndications = 3 + extraDrops
        link.send(SessionRequest.Ping)
        return id
    }

    @Test
    fun failedHandshakeOnKeptLinkRetriesWithGrowingDelay() = runTest {
        val h = LinkHarness(this)
        val id = h.keptLinkHandshake(extraDrops = 6) // dwa handshake'i bez odpowiedzi (BEGIN + 2 retransmisje każdy)
        h.settle(400_000)
        assertEquals(2, h.eventsOf(id).count { it == LinkEvent.HandshakeFailed })
        assertEquals(listOf(2_000L, 4_000L), h.retryDelays { it == LinkEvent.HandshakeFailed })
        assertEquals("Ready", h.namesOf(id).last())
        assertTrue("disconnect" !in h.radio.calls)
        // po udanym READY licznik wyzerowany: kolejny nieudany handshake → znów 2 s
        h.radio.dropIndications = 6
        h.link.send(SessionRequest.Ping)
        h.settle(400_000)
        assertEquals(2_000L, h.retryDelays { it == LinkEvent.HandshakeFailed }.last())
    }

    @Test
    fun retryableRejectOnKeptLinkBacksOff12_4() = runTest {
        val h = LinkHarness(this)
        val id = h.keptLinkHandshake(reject = HandshakeRejectReason.RATE_LIMITED)
        h.gate.rejectBegins.addLast(HandshakeRejectReason.RATE_LIMITED) // także pierwsze ponowienie
        h.settle(300_000)
        val rejected = LinkEvent.HandshakeRejected(HandshakeRejectReason.RATE_LIMITED)
        assertEquals(2, h.eventsOf(id).count { it == rejected })
        assertEquals(listOf(30_000L, 60_000L), h.retryDelays { it == rejected })
        assertEquals("Ready", h.namesOf(id).last())
        assertTrue("disconnect" !in h.radio.calls)
    }

    @Test
    fun terminalRejectOnKeptLinkStopsRetrying() = runTest {
        val h = LinkHarness(this)
        val id = h.keptLinkHandshake(reject = HandshakeRejectReason.AUTH_FAILED)
        h.settle(60_000)
        val rejected = LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED)
        assertEquals(rejected, h.eventsOf(id).last())
        h.settle(600_000)
        assertEquals(rejected, h.eventsOf(id).last()) // brak dalszych prób
        assertTrue("disconnect" !in h.radio.calls)
    }

    @Test
    fun noSessionLostWhileRadioAlive() = runTest {
        val h = LinkHarness(this)
        val id = h.keptLinkHandshake(extraDrops = 3)
        h.settle(200_000)
        assertTrue(h.eventsOf(id).none { it == LinkEvent.SessionLost })
        assertTrue(h.eventsOf(id).none { it == LinkEvent.Disconnected })
    }
}
