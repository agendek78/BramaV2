package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia czwartego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FourthReviewFixesTest {

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

    private fun TestScope.registering(): Harness {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        return h
    }

    @Test
    fun startupConnectFailureDoesNotKillActor() = runTest {
        val h = Harness(backgroundScope, ruleCache = FakeRuleCacheStore(LastKnownRule(snapshot(MONITORING), 1)))
        h.link.connectFails = true
        h.repo.start()
        runCurrent()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        assertEquals(SystemProblem.BLUETOOTH_OFF, h.state.systemProblem)
        h.link.connectFails = false
        h.repo.reportSystemProblem(null)
        h.repo.connect()
        runCurrent()
        assertEquals(listOf("connect"), h.link.calls)
    }

    @Test
    fun pairFailureEndsRegistrationAndAllowsRetry() = runTest {
        val h = registering()
        h.link.pairFails = true
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
        h.link.pairFails = false
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(listOf("pair"), h.link.calls)
        assertEquals(RegistrationState.Pairing, h.state.registration)
    }

    @Test
    fun connectFailureAfterPairingDoneFailsRegistration() = runTest {
        val h = registering()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.link.connectFails = true
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        // chwilowy błąd: ponowienie zaplanowane, rejestracja czeka na sesję
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        h.link.connectFails = false
        advanceTimeBy(2_001)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(RegistrationState.Done, h.state.registration)
    }

    @Test
    fun rejectDuringPairingAbortsPairingInLink() = runTest {
        val h = registering()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED))
        runCurrent()
        assertTrue("abort" in h.link.calls)
    }

    @Test
    fun notificationCanBeDismissedAndRepeats() = runTest {
        val h = Harness(backgroundScope, ruleCache = FakeRuleCacheStore(LastKnownRule(snapshot(MONITORING), 1)))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE) // ekran otwarty: łącze trwa także po DISARMED
        runCurrent()
        val reboot = snapshot(DISARMED, reason = RuleReason.REBOOT)
        h.link.emit(LinkEvent.Ready(reboot))
        runCurrent()
        assertEquals(UiMessage(MessageId.RULE_REBOOT_DISARMED), h.state.notification)
        h.repo.dismissNotification()
        runCurrent()
        assertNull(h.state.notification)
        h.link.emit(LinkEvent.RuleStatus(snapshot(MONITORING, revision = 2), applied = true))
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        h.link.emit(LinkEvent.Ready(reboot))
        runCurrent()
        assertEquals(UiMessage(MessageId.RULE_REBOOT_DISARMED), h.state.notification)
    }

    @Test
    fun cancellationIsNotTurnedIntoLockedCredential() = runTest {
        val h = Harness(backgroundScope)
        h.credentials.loadThrows = CancellationException("zakres zamknięty")
        h.repo.start()
        runCurrent()
        assertNull(h.state.systemProblem)
    }

    @Test
    fun pongWithFailedQueuedSendReevaluatesLink() = runTest {
        val h = ready(Harness(backgroundScope), source = UiSource.CAR)
        h.repo.refresh()
        h.repo.open()
        runCurrent()
        h.repo.uiDetached(UiSource.CAR)
        runCurrent()
        h.link.sendFails = true
        h.link.emit(LinkEvent.Pong(snapshot(DISARMED), applied = false))
        runCurrent()
        assertEquals("disconnect", h.link.calls.last())
    }

    @Test
    fun readyPublishesNoHalfAppliedState() = runTest {
        val h = Harness(backgroundScope)
        val seen = mutableListOf<GateUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.repo.state.toList(seen) }
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(MONITORING)))
        runCurrent()
        assertTrue(seen.none { it.connection == ConnectionState.READY && !it.rule.fresh })
        assertTrue(seen.last().connection == ConnectionState.READY && seen.last().rule.fresh)
    }
}
