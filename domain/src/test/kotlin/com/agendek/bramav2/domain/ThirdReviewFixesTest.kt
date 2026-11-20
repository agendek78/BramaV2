package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.BOOT_WAIT
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia trzeciego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThirdReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.ready(h: Harness, s: RuleSnapshot = snapshot(DISARMED)): Harness {
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun sends(h: Harness) = h.link.calls.filter { it.startsWith("send:") }

    @Test
    fun pingNotSentWhileCommandInFlight() = runTest {
        val h = ready(Harness(backgroundScope))
        h.repo.open()
        runCurrent()
        h.repo.uiAttached(UiSource.CAR)
        h.repo.refresh()
        runCurrent()
        assertEquals(listOf("send:OPEN(false)"), sends(h))
        assertEquals(true, h.state.rule.refreshing)
        val req = SessionRequest.Open(false)
        h.link.emit(LinkEvent.CommandResult(req, CommandAck(MessageType.OPEN.code, AckResult.OUTPUT_TRIGGERED, 1, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals(false, h.state.rule.refreshing)
        assertEquals(true, h.state.rule.fresh)
    }

    @Test
    fun commandSendFailureDoesNotLockControls() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.sendFails = true
        h.repo.open()
        runCurrent()
        assertNull(h.state.lastCommand) // nie wysłano — nie jest w toku ani nieznane
        h.link.sendFails = false
        advanceTimeBy(2_001) // odrzucony zapis = utrata łącza; ponowne połączenie po krótkim odstępie
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(true, h.state.controlsEnabled)
        h.repo.open()
        runCurrent()
        assertEquals(listOf("send:OPEN(false)"), sends(h))
    }

    @Test
    fun pingSendFailureDoesNotQueueLaterCommands() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.sendFails = true
        h.repo.refresh()
        runCurrent()
        h.link.sendFails = false
        advanceTimeBy(2_001)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.repo.open()
        runCurrent()
        assertEquals(listOf("send:OPEN(false)"), sends(h))
    }

    @Test
    fun pairingDropWithUnconfirmedOfOtherDeviceFailsRegistration() = runTest {
        val other = ByteArray(16) { 0x5a }
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED, deviceId = other)))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
    }

    @Test
    fun removeDuringCommandRecordsUnknownOutcome() = runTest {
        val h = ready(Harness(backgroundScope))
        h.repo.open()
        runCurrent()
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.history.appended.single().outcome)
    }

    @Test
    fun keyFailureAfterPairingDoneFailsRegistration() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.credentials.keyFails = true
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.CREDENTIAL_LOCKED)), h.state.registration)
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
    }

    @Test
    fun successfulOpenFalseClearsBackgroundBlocked() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true), keeper = FakeConnectionKeeper(allowed = false))
        ready(h)
        h.repo.open()
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        h.settings.setAutoCloseDefault(false)
        runCurrent()
        h.repo.open()
        runCurrent()
        assertNull(h.state.systemProblem)
    }

    @Test
    fun removeWarnsAboutCloseInOwnBootWait() = runTest {
        val h = ready(Harness(backgroundScope), snapshot(BOOT_WAIT, reason = RuleReason.REBOOT, remainingMs = 20_000))
        h.repo.requestRemoveFromPhone()
        runCurrent()
        assertTrue(UiMessage(MessageId.WARN_REMOVE_MAY_CLOSE) in h.state.warnings)
    }
}
