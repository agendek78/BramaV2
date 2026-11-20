package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.BLOCKED
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia końcowego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinalReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.ready(h: Harness, s: RuleSnapshot = snapshot(DISARMED), source: UiSource = UiSource.PHONE): Harness {
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(source)
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun lateReadyAfterUserDisconnectIsIgnored() = runTest {
        val h = ready(Harness(backgroundScope), snapshot(MONITORING))
        h.repo.requestDisconnect(UiSource.PHONE)
        h.repo.confirmDisconnect(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(MONITORING, revision = 2)))
        runCurrent()
        assertTrue(h.state.connection != ConnectionState.READY)
        assertEquals(false, h.state.controlsEnabled)
        assertEquals(false, h.state.rule.fresh)
    }

    @Test
    fun carDetachDuringOpenKeepsLinkUntilResolved() = runTest {
        val h = ready(Harness(backgroundScope), source = UiSource.CAR)
        h.repo.open()
        runCurrent()
        h.repo.uiDetached(UiSource.CAR)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        val req = SessionRequest.Open(false)
        h.link.emit(LinkEvent.CommandResult(req, CommandAck(MessageType.OPEN.code, AckResult.OUTPUT_TRIGGERED, 1, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals(CommandOutcome.OUTPUT_TRIGGERED, h.state.lastCommand!!.outcome)
        assertEquals("disconnect", h.link.calls.last())
    }

    @Test
    fun userDisconnectDuringCommandResolvesUnknownAndUnlocksControls() = runTest {
        val h = ready(Harness(backgroundScope))
        h.repo.open()
        runCurrent()
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
        h.repo.connect()
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(true, h.state.controlsEnabled)
    }

    @Test
    fun newReadyWithdrawsCommandQueuedBehindOldPing() = runTest {
        val h = ready(Harness(backgroundScope))
        h.repo.refresh()
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED))) // odtworzenie sesji (12.1) bez Pong
        runCurrent()
        assertEquals(listOf("send:PING"), h.link.calls.filter { it.startsWith("send:") })
        assertNull(h.state.lastCommand)
        assertEquals(true, h.state.controlsEnabled)
    }

    @Test
    fun reRegistrationAfterAuthFailedReconnectsOnLoss() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        val before = connects(h)
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(before + 1, connects(h))
    }

    @Test
    fun removeAfterUserDisconnectAllowsReconnectAfterRegistration() = runTest {
        val h = ready(Harness(backgroundScope))
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(false, h.state.connectSuspended)
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.Disconnected) // recovery 1 wymaga połączenia
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun storeFailureDoesNotStopActor() = runTest {
        val h = ready(Harness(backgroundScope))
        h.history.fail = true
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Open(false)))
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
    }

    @Test
    fun clientKeyFailureReportsLockedCredential() = runTest {
        val h = Harness(backgroundScope)
        h.credentials.keyFails = true
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
        assertEquals(0, connects(h))
        h.repo.reportSystemProblem(null)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem) // klucz nadal niedostępny
    }

    @Test
    fun markActiveFailureKeepsUnconfirmed() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.credentials.markActiveFails = true
        ready(h)
        assertEquals(CredentialState.UNCONFIRMED, h.state.credential)
        assertEquals(false, h.state.controlsEnabled)
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
    }

    @Test
    fun blockedOwnRuleKeepsLinkAfterDetach() = runTest {
        val h = ready(Harness(backgroundScope), snapshot(BLOCKED, reason = RuleReason.INTERLOCK))
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
    }

    @Test
    fun removeDuringPairingAbortsItAndGrantCannotResurrectCredential() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertTrue("abort" in h.link.calls)
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertNull(h.credentials.state)
        assertTrue(h.credentials.saved.isEmpty())
        assertTrue("commit" !in h.link.calls)
        assertNull(h.state.registration)
    }

    @Test
    fun cancelRegistrationRestoresConnection() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.repo.cancelRegistration()
        runCurrent()
        assertEquals(listOf("connect", "disconnect", "pair", "abort", "connect"), h.link.calls)
        assertTrue(h.state.connection != ConnectionState.CONNECTING || h.link.calls.last() == "connect")
    }

    @Test
    fun failedRegistrationShowsDisconnectedAndReconnectsUnconfirmed() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED))
        runCurrent()
        assertEquals("connect", h.link.calls.last())
    }

    @Test
    fun unlockedCredentialIsReloaded() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(0, connects(h))
        h.credentials.current = FakeCredentialStore.present(CredentialState.ACTIVE)
        h.repo.reportSystemProblem(null)
        runCurrent()
        assertEquals(1, connects(h))
        assertNull(h.state.systemProblem)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
    }

    @Test
    fun stillLockedCredentialKeepsProblemAfterClear() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.repo.reportSystemProblem(null)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
    }
}
