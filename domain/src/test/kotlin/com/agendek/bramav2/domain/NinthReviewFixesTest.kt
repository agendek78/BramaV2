package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testy odtwarzające ustalenia dziewiątego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class NinthReviewFixesTest {

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope), s: RuleSnapshot = snapshot(DISARMED)): Harness {
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun failedReconnectAfterSessionLossIsRetried() = runTest {
        val h = ready(s = snapshot(MONITORING))
        h.repo.uiDetached(UiSource.PHONE) // łącze trzyma tylko własna uzbrojona reguła
        runCurrent()
        h.link.connectFails = true
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(0, connects(h))
        h.link.connectFails = false
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun refreshingClearedWhenCommandEndsUnknown() = runTest {
        val h = ready()
        h.repo.open()
        h.repo.refresh()
        runCurrent()
        assertEquals(true, h.state.rule.refreshing)
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Open(false)))
        runCurrent()
        assertEquals(false, h.state.rule.refreshing)
    }

    @Test
    fun disablingAutoCloseDropsPendingEnableConfirmation() = runTest {
        val h = ready()
        h.repo.setAutoCloseDefault(true)
        runCurrent()
        h.repo.setAutoCloseDefault(false)
        runCurrent()
        assertTrue(h.state.confirmations.isEmpty())
        h.repo.confirmEnableAutoClose()
        runCurrent()
        assertEquals(false, h.settings.autoClose.value)
    }

    @Test
    fun disconnectConfirmationDroppedOnSessionLoss() = runTest {
        val h = ready(s = snapshot(MONITORING))
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertTrue(h.state.confirmations.isEmpty())
        assertTrue(h.state.warnings.isEmpty())
    }

    @Test
    fun secondStartIsIgnored() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.start()
        runCurrent()
        assertEquals(1, h.link.subscribers) // jeden aktor, jedna subskrypcja zdarzeń łącza
    }

    @Test
    fun rejectedWriteIsTreatedAsLinkLoss() = runTest {
        val h = ready()
        h.link.sendFails = true
        h.repo.open()
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
        h.link.sendFails = false
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, connects(h))
    }
}
