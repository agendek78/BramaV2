package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
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

/** Testy odtwarzające ustalenia ósmego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EighthReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun strayDisconnectDoesNotShortenBackoff() = runTest {
        val h = ready()
        h.link.emit(LinkEvent.SessionLost) // zerwana sesja → nowa próba (jeszcze przed READY)
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(0, connects(h))
        advanceTimeBy(28_000)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun handshakeFailedRetriesQuicklyWithoutConsumingBackoff() = runTest {
        val h = ready()
        h.link.emit(LinkEvent.SessionLost) // nowa próba (przed READY) — błędy handshake dotyczą jej, nie zachowanego łącza
        runCurrent()
        h.link.calls.clear()
        // kolejne nieudane próby: rosnący krótki odstęp 2 s, 4 s, 8 s (bez zużywania backoffu 12.4)
        listOf(2_001L, 4_001L, 8_001L).forEachIndexed { i, wait ->
            h.link.emit(LinkEvent.HandshakeFailed)
            runCurrent()
            advanceTimeBy(wait)
            runCurrent()
            assertEquals(i + 1, connects(h))
        }
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        advanceTimeBy(30_001) // licznik nietknięty przez HandshakeFailed: pierwszy odstęp 30 s
        runCurrent()
        assertEquals(4, connects(h))
    }

    @Test
    fun detachingSourceDropsItsConfirmation() = runTest {
        val h = ready()
        h.repo.uiAttached(UiSource.CAR)
        runCurrent()
        h.repo.requestClose(UiSource.CAR)
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.uiDetached(UiSource.CAR)
        runCurrent()
        assertEquals(setOf(UiSource.PHONE), h.state.confirmations.keys)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue(h.state.confirmations.isEmpty())
        assertTrue(h.state.warnings.isEmpty())
    }

    @Test
    fun rateLimitedDuringPairingAppliesBackoff() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED)))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.RATE_LIMITED))
        runCurrent()
        assertEquals(0, connects(h))
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun refreshReloadsUnlockedCredential() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.credentials.current = FakeCredentialStore.present(CredentialState.ACTIVE)
        h.repo.refresh()
        runCurrent()
        assertEquals(1, connects(h))
        assertNull(h.state.systemProblem)
    }

    @Test
    fun removeClearsLockedCredentialProblem() = runTest {
        val h = Harness(backgroundScope)
        h.credentials.keyFails = true
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertNull(h.state.systemProblem)
        assertEquals(CredentialState.NONE, h.state.credential)
    }
}
