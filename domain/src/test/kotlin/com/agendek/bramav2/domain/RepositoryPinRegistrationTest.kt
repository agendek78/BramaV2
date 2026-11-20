package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.PairingSecret
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rejestracja PIN-em (spec 11.4, pin_presence_design §5.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryPinRegistrationTest {

    private val otherDevice = ByteArray(16) { 0x2b }
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
    fun pinRegistrationCallsPairWithPin() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        val secret = h.link.lastPairSecret as PairingSecret.Pin
        assertEquals(6, secret.pin.size)
        assertEquals(RegistrationState.Pairing, h.state.registration)
    }

    @Test
    fun invalidPinIsIgnoredWithoutPair() = runTest {
        val h = started()
        h.link.calls.clear()
        h.repo.startPinRegistration("12345")
        h.repo.startPinRegistration("12a456")
        runCurrent()
        assertTrue("pair" !in h.link.calls)
        assertEquals(null, h.state.registration)
    }

    @Test
    fun grantSavesDeviceIdFromEvent() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(grantedKey.copyOf(), otherDevice.copyOf()))
        runCurrent()
        val stored = (h.credentials.current as CredentialLoad.Present).credential
        assertArrayEquals(otherDevice, stored.deviceId)
        assertEquals(CredentialState.UNCONFIRMED, stored.state)
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(RegistrationState.Done, h.state.registration)
    }

    @Test
    fun pinRegistrationReusesUnconfirmedClientId() = runTest {
        val clientId = ByteArray(16) { 0x7c }
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED, otherDevice, clientId)))
        h.repo.startPinRegistration("482915")
        runCurrent()
        assertArrayEquals(clientId, h.link.lastPairClientId)
    }

    @Test
    fun pinRegistrationWithoutCredentialUsesNewClientId() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        assertArrayEquals(FakeNonceSource().next(16), h.link.lastPairClientId)
    }

    @Test
    fun pinAuthFailedShowsPinMessage() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.PIN_REGISTRATION_FAILED)), h.state.registration)
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.PIN_REGISTRATION_FAILED)), h.state.registration)
    }

    @Test
    fun pinOtherRejectKeepsItsMessage() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.NO_CAPACITY))
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REJECT_NO_CAPACITY)), h.state.registration)
    }

    @Test
    fun pinIsWipedAfterAttempt() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        val secret = h.link.lastPairSecret as PairingSecret.Pin
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertTrue(secret.pin.all { it == 0.toByte() })
    }

    @Test
    fun pinRegistrationNotAvailableWithActive() = runTest {
        val h = started(FakeCredentialStore(FakeCredentialStore.present(CredentialState.ACTIVE)))
        h.link.calls.clear()
        h.repo.startPinRegistration("482915")
        runCurrent()
        assertTrue("pair" !in h.link.calls)
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REJECT_CLIENT_ALREADY_ACTIVE)), h.state.registration)
    }

    @Test
    fun recoveryAfterLostDoneWorksForPin() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(grantedKey.copyOf(), otherDevice.copyOf()))
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
    }

    @Test
    fun disconnectBeforeGrantFailsPinRegistration() = runTest {
        val h = started()
        h.repo.startPinRegistration("482915")
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertTrue(h.state.registration is RegistrationState.Failed)
    }
}
