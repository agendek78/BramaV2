package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.QrError
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryRegistrationTest {

    private val deviceId = FakeCredentialStore.DEVICE_ID
    private val setupKey = ByteArray(32) { 0x44 }
    private val validQr =
        "brama://provision?v=2&id=${deviceId.toHex()}&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(setupKey)}&p=482915"
    private val grantedKey = ByteArray(32) { 0x66 }

    private fun TestScope.started(credentials: FakeCredentialStore = FakeCredentialStore(CredentialLoad.None)): Harness {
        val h = Harness(backgroundScope, credentials = credentials)
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        return h
    }

    @Test
    fun registrationBlockedWhenActive() = runTest {
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.ACTIVE)))
        h.link.calls.clear()
        h.repo.startRegistration(validQr)
        runCurrent()
        assertTrue("pair" !in h.link.calls)
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REJECT_CLIENT_ALREADY_ACTIVE)), h.state.registration)
    }

    @Test
    fun invalidQrDoesNotTouchStore() = runTest {
        val h = started()
        h.repo.startRegistration(validQr.replace("v=2", "v=3"))
        runCurrent()
        assertEquals(RegistrationState.InvalidQr(QrError.UNSUPPORTED_VERSION), h.state.registration)
        assertTrue(h.credentials.saved.isEmpty())
        assertTrue("pair" !in h.link.calls)
    }

    @Test
    fun happyPathPairsSavesCommitsAndConfirms() = runTest {
        val h = started()
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(RegistrationState.Pairing, h.state.registration)
        h.link.emit(LinkEvent.GrantReceived(grantedKey.copyOf(), FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertEquals(CredentialState.UNCONFIRMED, h.credentials.state)
        assertEquals(CredentialState.UNCONFIRMED, h.state.credential)
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(listOf("pair", "commit", "connect"), h.link.calls)
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        assertEquals(false, h.state.controlsEnabled)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(CredentialState.ACTIVE, h.credentials.state)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        assertEquals(UiMessage(MessageId.REGISTRATION_DONE), h.state.notification)
        assertEquals(RegistrationState.Done, h.state.registration)
        assertEquals(true, h.state.controlsEnabled)
    }

    @Test
    fun newClientIdIs16RandomBytes() = runTest {
        val h = started()
        h.repo.startRegistration(validQr)
        runCurrent()
        assertArrayEquals(FakeNonceSource().next(16), h.link.lastPairClientId)
    }

    @Test
    fun unconfirmedSameDeviceKeepsClientId() = runTest {
        val clientId = ByteArray(16) { 0x7c }
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED, deviceId, clientId)))
        h.repo.startRegistration(validQr)
        runCurrent()
        assertArrayEquals(clientId, h.link.lastPairClientId)
    }

    @Test
    fun saveFailureAbortsPairing() = runTest {
        val h = started()
        h.credentials.saveFails = true
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(grantedKey.copyOf(), FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertTrue("abort" in h.link.calls)
        assertTrue("commit" !in h.link.calls)
        assertTrue(h.state.registration is RegistrationState.Failed)
    }

    @Test
    fun rejectDuringPairingKeepsCredentials() = runTest {
        val clientId = ByteArray(16) { 0x7c }
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED, deviceId, clientId)))
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.WINDOW_CLOSED))
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REJECT_WINDOW_CLOSED)), h.state.registration)
        assertEquals(CredentialState.UNCONFIRMED, h.credentials.state)
        assertEquals(false, h.credentials.deleted)
    }

    @Test
    fun unconfirmedAtStartupConfirmsOnFirstReady() = runTest {
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        assertEquals(listOf("connect"), h.link.calls)
        h.repo.open()
        runCurrent()
        assertTrue(h.link.calls.none { it.startsWith("send:") })
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(1, h.credentials.markActiveCalls)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        assertEquals(true, h.state.controlsEnabled)
    }

    @Test
    fun unconfirmedAuthFailedShowsRegistrationIncomplete() = runTest {
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        assertEquals(UiMessage(MessageId.REGISTRATION_INCOMPLETE), h.state.handshakeProblem)
        assertEquals(CredentialState.UNCONFIRMED, h.credentials.state)
    }

    @Test
    fun existingSessionIsDroppedBeforePairing() = runTest {
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(listOf("connect", "disconnect", "pair"), h.link.calls)
    }

    @Test
    fun cancelRegistrationAborts() = runTest {
        val h = started()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.repo.cancelRegistration()
        runCurrent()
        assertTrue("abort" in h.link.calls)
        assertNull(h.state.registration)
    }

    @Test
    fun linkLostAfterGrantTriesSessionRecovery() = runTest {
        val h = started()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(grantedKey.copyOf(), FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.link.emit(LinkEvent.Disconnected) // COMMIT/DONE utracone (spec 11.3 recovery 1)
        runCurrent()
        assertEquals(listOf("pair", "commit", "connect"), h.link.calls)
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
    }
}
