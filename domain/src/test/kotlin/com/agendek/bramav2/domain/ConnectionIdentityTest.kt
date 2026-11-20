package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.HandshakeRejectReason
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

/** Identyfikator połączenia w porcie GateLink, kompromis pętli READY i ustalenia trzynastego przeglądu. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionIdentityTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun queuedEventOfOldConnectionDoesNotHitNewOne() = runTest {
        val h = ready()
        val old = h.link.currentId
        // SessionLost i Disconnected starego połączenia trafiają do kolejki razem
        h.link.emitFrom(old, LinkEvent.SessionLost)
        h.link.emitFrom(old, LinkEvent.Disconnected)
        runCurrent()
        assertEquals(listOf("disconnect", "connect"), h.link.calls) // nowa próba nienaruszona
        h.link.emitFrom(old, LinkEvent.Ready(snapshot(MONITORING)))
        runCurrent()
        assertTrue(h.state.connection != ConnectionState.READY)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED))) // zdarzenie nowego połączenia
        runCurrent()
        assertEquals(ConnectionState.READY, h.state.connection)
    }

    @Test
    fun repeatedReadyLossCyclesAreThrottledAfterThree() = runTest {
        val h = ready()
        repeat(3) {
            h.link.emit(LinkEvent.SessionLost)
            runCurrent()
            h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
            runCurrent()
        }
        assertEquals(3, connects(h)) // pierwsze trzy: natychmiast
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(3, connects(h)) // czwarty cykl: krótki odstęp
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(4, connects(h))
    }

    @Test
    fun userActionResetsReadyLossCounter() = runTest {
        val h = ready()
        repeat(3) {
            h.link.emit(LinkEvent.SessionLost)
            runCurrent()
            h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
            runCurrent()
        }
        h.repo.refresh() // świadoma akcja użytkownika
        runCurrent()
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(4, connects(h))
    }

    @Test
    fun retryableRejectWhileConfirmingKeepsConfirming() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        val before = connects(h)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(before + 1, connects(h)) // potwierdzanie nadal powodem połączenia
    }

    @Test
    fun pairingDoneWithoutGrantInThisAttemptFailsEvenWithOldUnconfirmed() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr) // ten sam sterownik co stare UNCONFIRMED
        runCurrent()
        h.link.emit(LinkEvent.PairingDone) // bez GRANT w tej próbie
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
    }
}
