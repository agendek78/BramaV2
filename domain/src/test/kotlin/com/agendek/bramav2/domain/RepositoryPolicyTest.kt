package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.FAULT
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryPolicyTest {

    private suspend fun TestScope.readyHarness(
        h: Harness = Harness(backgroundScope),
        s: RuleSnapshot = snapshot(DISARMED),
    ): Harness {
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
    fun disconnectWithOwnRuleNeedsConfirmation() = runTest {
        val h = readyHarness(s = snapshot(MONITORING))
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertEquals(Confirmation.DISCONNECT_ARMS_AUTOCLOSE, h.state.confirmations[UiSource.PHONE])
        assertEquals(listOf(UiMessage(MessageId.WARN_DISCONNECT_ARMS)), h.state.warnings)
        h.repo.confirmDisconnect(UiSource.PHONE)
        runCurrent()
        assertEquals(listOf("disconnect"), h.link.calls)
        assertEquals(true, h.state.connectSuspended)
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun disconnectWithoutOwnRuleIsImmediate() = runTest {
        val h = readyHarness()
        h.repo.requestDisconnect(UiSource.CAR)
        runCurrent()
        assertEquals(listOf("disconnect"), h.link.calls)
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun afterUserDisconnectNoAutoReconnect() = runTest {
        val h = readyHarness(s = snapshot(MONITORING))
        h.repo.requestDisconnect(UiSource.PHONE)
        h.repo.confirmDisconnect(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        h.repo.uiAttached(UiSource.PHONE)
        h.repo.uiAttached(UiSource.CAR)
        h.repo.refresh()
        runCurrent()
        assertEquals(0, connects(h))
        h.repo.connect()
        runCurrent()
        assertEquals(1, connects(h))
        assertEquals(false, h.state.connectSuspended)
    }

    @Test
    fun retryableRejectBacksOff() = runTest {
        val h = readyHarness()
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        assertEquals(UiMessage(MessageId.REJECT_UNAVAILABLE), h.state.handshakeProblem)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(0, connects(h))
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, connects(h))
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.RATE_LIMITED))
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, connects(h))
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, connects(h))
    }

    @Test
    fun uiAttachDuringBackoffDoesNotShortenIt() = runTest {
        val h = readyHarness()
        h.link.emit(LinkEvent.SessionLost) // nowa próba (przed READY) — błędy handshake dotyczą jej, nie zachowanego łącza
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        h.link.calls.clear()
        advanceTimeBy(10_000)
        h.repo.uiAttached(UiSource.CAR)
        h.repo.uiDetached(UiSource.PHONE)
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(0, connects(h))
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun readyClearsHandshakeProblem() = runTest {
        val h = readyHarness()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertNull(h.state.handshakeProblem)
    }

    @Test
    fun handshakeFailedBacksOffLikeRetryableReject() = runTest {
        val h = readyHarness()
        h.link.emit(LinkEvent.SessionLost) // nowa próba (przed READY) — błędy handshake dotyczą jej, nie zachowanego łącza
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertEquals(0, connects(h))
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun authFailedOnActiveIsNotRetriedAndKeepsCredential() = runTest {
        val h = readyHarness()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        assertEquals(false, h.credentials.deleted)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        assertEquals(UiMessage(MessageId.REJECT_AUTH_FAILED), h.state.handshakeProblem)
        h.repo.uiDetached(UiSource.PHONE)
        h.repo.uiAttached(UiSource.PHONE)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, connects(h))
        h.repo.connect()
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun enablingAutoCloseNeedsConfirmation() = runTest {
        val h = readyHarness()
        h.repo.setAutoCloseDefault(true)
        runCurrent()
        assertEquals(false, h.settings.autoClose.value)
        assertEquals(Confirmation.ENABLE_AUTO_CLOSE, h.state.confirmations[UiSource.PHONE])
        assertEquals(listOf(UiMessage(MessageId.WARN_ENABLE_AUTO_CLOSE)), h.state.warnings)
        h.repo.confirmEnableAutoClose()
        runCurrent()
        assertEquals(true, h.settings.autoClose.value)
        assertTrue(h.state.confirmations.isEmpty())
        h.repo.setAutoCloseDefault(false)
        runCurrent()
        assertEquals(false, h.settings.autoClose.value)
    }

    @Test
    fun removeWarnsAboutCloseOnlyForOwnMonitoringOrStale() = runTest {
        val notRevoked = UiMessage(MessageId.WARN_REMOVE_NOT_REVOKED)
        val mayClose = UiMessage(MessageId.WARN_REMOVE_MAY_CLOSE)

        val own = readyHarness(s = snapshot(MONITORING))
        own.repo.requestRemoveFromPhone()
        runCurrent()
        assertEquals(Confirmation.REMOVE_FROM_PHONE, own.state.confirmations[UiSource.PHONE])
        assertEquals(setOf(notRevoked, mayClose), own.state.warnings.toSet())

        val fault = readyHarness(Harness(backgroundScope), snapshot(FAULT, reason = RuleReason.IO_FAULT))
        fault.repo.requestRemoveFromPhone()
        runCurrent()
        assertEquals(listOf(notRevoked), fault.state.warnings)

        val stale = readyHarness(Harness(backgroundScope), snapshot(FAULT, reason = RuleReason.IO_FAULT))
        stale.link.emit(LinkEvent.SessionLost)
        runCurrent()
        stale.repo.requestRemoveFromPhone()
        runCurrent()
        assertEquals(setOf(notRevoked, mayClose), stale.state.warnings.toSet())
    }

    @Test
    fun confirmRemoveDeletesCredentialAndKeepsHistory() = runTest {
        val h = readyHarness()
        h.history.appended += HistoryEntry(1, GateCommand.Close, CommandOutcome.OUTPUT_TRIGGERED, null)
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
        assertEquals(true, h.credentials.deleted)
        assertEquals(true, h.ruleCache.cleared)
        assertEquals(CredentialState.NONE, h.state.credential)
        assertEquals(1, h.history.appended.size)
    }

    @Test
    fun confirmRemoveWithoutRequestDoesNothing() = runTest {
        val h = readyHarness()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(false, h.credentials.deleted)
    }

    @Test
    fun lateReadyAfterRemoveIsIgnored() = runTest {
        val h = readyHarness()
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(MONITORING)))
        runCurrent()
        assertTrue(h.state.connection != ConnectionState.READY)
        assertNull(h.state.rule.snapshot)
    }
}
