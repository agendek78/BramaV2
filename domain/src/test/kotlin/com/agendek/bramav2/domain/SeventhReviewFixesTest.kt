package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia siódmego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeventhReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"
    private val incomplete = UiMessage(MessageId.REGISTRATION_INCOMPLETE)

    private fun TestScope.pairing(): Harness {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun connectFailureAfterPairingDoneShowsDisconnected() = runTest {
        val h = pairing()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.link.connectFails = true
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
    }

    @Test
    fun pairingDoneAfterUserDisconnectShowsDisconnected() = runTest {
        val h = pairing()
        h.repo.requestDisconnect(UiSource.PHONE)
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
    }

    @Test
    fun failedAttemptBeforeReadyReconnectsAfterShortDelay() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(1, connects(h))
        h.link.emit(LinkEvent.Disconnected) // np. sterownik poza zasięgiem
        runCurrent()
        assertEquals(1, connects(h))
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, connects(h))
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, connects(h))
    }

    @Test
    fun keeperExceptionIsTreatedAsBackgroundStartBlocked() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true))
        h.keeper.throws = true
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(com.agendek.bramav2.protocol.AutoClosePhase.DISARMED)))
        runCurrent()
        h.repo.open()
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        assertTrue(h.link.calls.none { it.startsWith("send:") })
    }

    @Test
    fun handshakeFailedDuringPairingShowsRegistrationIncomplete() = runTest {
        val h = pairing()
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertEquals(RegistrationState.Failed(incomplete), h.state.registration)
    }
}
