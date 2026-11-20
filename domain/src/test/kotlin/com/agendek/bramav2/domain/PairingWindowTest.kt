package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Okno rejestracji otwierane z telefonu (phone_window_design §5). */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingWindowTest {

    private val supported = LinkInfo(firmwareVersion = "0.1.0", otaSupported = true, mtu = 247, pairingWindowSupported = true)

    private suspend fun TestScope.ready(info: LinkInfo = supported): Harness {
        val h = Harness(backgroundScope)
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info))
        runCurrent()
        h.link.calls.clear()
        h.link.sent.clear()
        return h
    }

    private suspend fun TestScope.ack(h: Harness, result: AckResult) {
        h.link.emit(LinkEvent.CommandResult(SessionRequest.OpenPairingWindow, CommandAck(0x03, result, 1, snapshot(DISARMED)), true))
        runCurrent()
    }

    @Test
    fun availableOnlyWithBit7OnReadySession() = runTest {
        assertTrue(ready().state.pairingWindowAvailable)
        assertFalse(ready(supported.copy(pairingWindowSupported = false)).state.pairingWindowAvailable)
        val h = ready()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertFalse(h.state.pairingWindowAvailable)
    }

    @Test
    fun windowOpenedShowsNotificationWithoutHistory() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        assertEquals(listOf<SessionRequest>(SessionRequest.OpenPairingWindow), h.link.sent)
        assertFalse(h.state.pairingWindowAvailable) // jedno żądanie naraz (13.3)
        ack(h, AckResult.WINDOW_OPENED)
        assertEquals(UiMessage(MessageId.PAIRING_WINDOW_OPENED), h.state.notification)
        assertTrue(h.state.pairingWindowAvailable)
        assertNull(h.state.lastCommand) // to nie jest komenda bramy
        assertTrue(h.history.appended.isEmpty())
    }

    @Test
    fun otherResultIsCompatibilityError() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        ack(h, AckResult.UNSUPPORTED_TYPE)
        assertEquals(UiMessage(MessageId.RESULT_COMPATIBILITY_ERROR), h.state.notification)
    }

    @Test
    fun unknownOutcomeClearsRequestSilently() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.OpenPairingWindow))
        runCurrent()
        assertNull(h.state.notification)
        assertNull(h.state.lastCommand)
    }

    @Test
    fun gateCommandWaitsForWindowResult() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals(1, h.link.sent.size) // CLOSE czeka (13.3)
        assertEquals(CommandOutcome.IN_FLIGHT, h.state.lastCommand!!.outcome)
        ack(h, AckResult.WINDOW_OPENED)
        assertEquals(SessionRequest.Close, h.link.sent.last())
    }

    @Test
    fun supportFlagFollowsBit7() = runTest { // akcja ukryta przy firmware bez funkcji (§3)
        assertTrue(ready().state.pairingWindowSupported)
        assertFalse(ready(supported.copy(pairingWindowSupported = false)).state.pairingWindowSupported)
    }

    @Test
    fun unknownOutcomeDuringRefreshMarksRuleStale() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        h.repo.refresh() // PING nie wychodzi przy żądaniu w toku
        runCurrent()
        assertTrue(h.state.rule.refreshing)
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.OpenPairingWindow))
        runCurrent()
        assertFalse(h.state.rule.refreshing)
        assertFalse(h.state.rule.fresh)
    }

    @Test
    fun mismatchedResultWhileWindowPendingRestartsSession() = runTest {
        val h = ready()
        h.repo.openPairingWindow()
        runCurrent()
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, CommandAck(0x02, AckResult.OUTPUT_TRIGGERED, 1, snapshot(DISARMED)), true))
        runCurrent()
        assertTrue("disconnect" in h.link.calls) // wynik dla innego żądania: stan niewiarygodny (13.4)
        assertFalse(h.state.pairingWindowAvailable)
    }

    @Test
    fun ignoredWhenUnavailable() = runTest {
        val h = ready(supported.copy(pairingWindowSupported = false))
        h.repo.openPairingWindow()
        runCurrent()
        assertTrue(h.link.sent.isEmpty())
        val busy = ready()
        busy.repo.requestClose(UiSource.PHONE)
        runCurrent()
        busy.repo.openPairingWindow()
        runCurrent()
        assertEquals(listOf<SessionRequest>(SessionRequest.Close), busy.link.sent)
    }
}
