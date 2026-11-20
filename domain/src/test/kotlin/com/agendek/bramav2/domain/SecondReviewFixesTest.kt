package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.QrError
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

/** Testy odtwarzające ustalenia drugiego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondReviewFixesTest {

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

    @Test
    fun registrationRefusedWhileCredentialLocked() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        assertTrue("pair" !in h.link.calls)
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.CREDENTIAL_LOCKED)), h.state.registration)
    }

    @Test
    fun registrationRefusedWhenUnlockRevealsActiveCredential() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.credentials.current = FakeCredentialStore.present(CredentialState.ACTIVE)
        h.repo.startRegistration(validQr)
        runCurrent()
        assertTrue("pair" !in h.link.calls)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
    }

    @Test
    fun failedDeleteKeepsLinkAndCredential() = runTest {
        val h = ready(Harness(backgroundScope))
        h.credentials.deleteFails = true
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        assertEquals(ConnectionState.READY, h.state.connection)
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun appProblemDoesNotHideLockedCredential() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
    }

    @Test
    fun backgroundBlockedSurvivesAppClearAndClearsOnSuccessfulOpen() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true), keeper = FakeConnectionKeeper(allowed = false))
        ready(h)
        h.repo.open()
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF) // zrywa sesję
        runCurrent()
        h.repo.reportSystemProblem(null) // ponowne połączenie
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.keeper.allowed = true
        h.repo.open()
        runCurrent()
        assertNull(h.state.systemProblem)
        assertEquals(listOf("send:OPEN(true)"), h.link.calls.filter { it.startsWith("send:") })
    }

    @Test
    fun missingCredentialAfterUnlockClearsLockProblem() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        runCurrent()
        h.credentials.current = CredentialLoad.None
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertNull(h.state.systemProblem)
        assertEquals(CredentialState.NONE, h.state.credential)
    }

    @Test
    fun startingRegistrationOverLiveSessionMarksRuleStale() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.credentials.markActiveFails = true // READY bez awansu do ACTIVE
        ready(h)
        assertEquals(true, h.state.rule.fresh)
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(false, h.state.rule.fresh) // brak sesji READY → reguła nieaktualna
        assertEquals(listOf("disconnect", "pair"), h.link.calls)
    }

    @Test
    fun cancelDismissesInvalidQrAndFailedRegistration() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr.replace("v=2", "v=3"))
        runCurrent()
        assertEquals(RegistrationState.InvalidQr(QrError.UNSUPPORTED_VERSION), h.state.registration)
        h.repo.cancelRegistration()
        runCurrent()
        assertNull(h.state.registration)
        assertTrue("abort" !in h.link.calls)
    }

    @Test
    fun lostPingWithQueuedCommandReevaluatesLink() = runTest {
        val h = ready(Harness(backgroundScope), source = UiSource.CAR)
        h.repo.refresh()
        h.repo.open()
        runCurrent()
        h.repo.uiDetached(UiSource.CAR)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls) // komenda czeka — łącze utrzymane
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Ping))
        runCurrent()
        assertEquals("disconnect", h.link.calls.last())
    }
}
