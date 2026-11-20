package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ble_design §4.3a: błędy handshake na łączu, które osiągnęło READY, nie zrywają BLE właściciela. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptLinkRecoveryTest {

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    @Test
    fun keptLinkHandshakeFailedDoesNotDisconnect() = runTest {
        val h = ready()
        h.link.emit(LinkEvent.Rekeying)
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertEquals(ConnectionState.REKEYING, h.state.connection)
        assertEquals(false, h.state.controlsEnabled)
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue("connect" !in h.link.calls) // ponowienia robi :ble na tym samym łączu
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(ConnectionState.READY, h.state.connection)
    }

    @Test
    fun keptLinkRetryableRejectDoesNotDisconnect() = runTest {
        val h = ready()
        h.link.emit(LinkEvent.Rekeying)
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.RATE_LIMITED))
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertEquals(UiMessage(MessageId.REJECT_RATE_LIMITED), h.state.handshakeProblem)
        assertEquals(false, h.state.rule.fresh)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertNull(h.state.handshakeProblem)
    }

    @Test
    fun keptLinkTerminalRejectStillTearsDown() = runTest {
        val h = ready()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun attemptWithoutReadyStillTearsDown() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }
}
