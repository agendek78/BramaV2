package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.CancellationException
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

/** Testy odtwarzające ustalenia piątego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FifthReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"
    private val incomplete = UiMessage(MessageId.REGISTRATION_INCOMPLETE)

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

    /** Bez poświadczenia, ekran telefonu podpięty, próba PAIR rozpoczęta. */
    private fun TestScope.pairing(): Harness {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        return h
    }

    @Test
    fun markActiveFailureIsShownAndRetriedOnUserAction() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.credentials.markActiveFails = true
        ready(h)
        assertEquals(incomplete, h.state.handshakeProblem)
        h.credentials.markActiveFails = false
        h.repo.refresh()
        runCurrent()
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        assertEquals(true, h.state.controlsEnabled)
        assertNull(h.state.handshakeProblem)
    }

    @Test
    fun handshakeFailedWhileConfirmingKeepsConfirmingAndRetries() = runTest {
        val h = pairing()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        h.repo.uiDetached(UiSource.PHONE) // także bez otwartego ekranu
        h.link.emit(LinkEvent.HandshakeFailed) // błąd transportu, nie odmowa
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        val before = h.link.calls.count { it == "connect" }
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(before + 1, h.link.calls.count { it == "connect" })
    }

    @Test
    fun commitFailureEndsAttemptAndFallsBackToSession() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.link.commitFails = true
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertEquals(RegistrationState.Failed(incomplete), h.state.registration)
        assertEquals(listOf("pair", "abort", "connect"), h.link.calls)
    }

    @Test
    fun removeCompletesEvenWhenDisconnectThrows() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.disconnectFails = true
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(CredentialState.NONE, h.state.credential)
        assertEquals(true, h.ruleCache.cleared)
        assertTrue(h.state.connection != ConnectionState.READY)
    }

    @Test
    fun cancelRegistrationCompletesEvenWhenAbortThrows() = runTest {
        val h = pairing()
        h.link.abortFails = true
        h.repo.cancelRegistration()
        runCurrent()
        assertNull(h.state.registration)
        h.link.abortFails = false
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(2, h.link.calls.count { it == "pair" })
    }

    @Test
    fun failedRuleCacheSaveIsRetried() = runTest {
        val h = Harness(backgroundScope)
        h.ruleCache.failNextSave = true
        ready(h, snapshot(MONITORING))
        assertTrue(h.ruleCache.saved.isEmpty())
        h.link.emit(LinkEvent.RuleStatus(snapshot(MONITORING, revision = 2), applied = true))
        runCurrent()
        assertEquals(MONITORING, h.ruleCache.saved.single().snapshot.phase)
    }

    @Test
    fun cancellationDuringGrantSaveIsNotTreatedAsFailure() = runTest {
        val h = pairing()
        h.credentials.saveThrows = CancellationException("zakres zamknięty")
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertTrue("abort" !in h.link.calls)
        assertTrue(h.state.registration !is RegistrationState.Failed)
    }

    @Test
    fun pairingDoneRespectsUserDisconnect() = runTest {
        val h = pairing()
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertTrue("connect" !in h.link.calls)
        h.repo.connect()
        runCurrent()
        assertEquals("connect", h.link.calls.last())
    }

}
