package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.RuleSnapshot
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

/** Testy odtwarzające ustalenia szóstego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class SixthReviewFixesTest {

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

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun registrationCompletesWithoutAttachedUi() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(CredentialState.ACTIVE, h.credentials.state)
        assertEquals(RegistrationState.Done, h.state.registration)
    }

    @Test
    fun clearingSystemProblemRetriesConnection() = runTest {
        val h = Harness(backgroundScope)
        h.link.connectFails = true
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        h.link.connectFails = false
        h.repo.reportSystemProblem(null)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun handshakeRejectDisconnectsLink() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun handshakeFailedDisconnectsLink() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.emit(LinkEvent.SessionLost) // nowa próba (przed READY) — błędy handshake dotyczą jej, nie zachowanego łącza
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeFailed)
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun strayGrantKeyIsZeroed() = runTest {
        val h = ready(Harness(backgroundScope))
        val key = ByteArray(32) { 0x66 }
        h.link.emit(LinkEvent.GrantReceived(key, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun uiAttachResetsNextBackoffWithoutShorteningCurrentWait() = runTest {
        val h = ready(Harness(backgroundScope))
        h.link.emit(LinkEvent.SessionLost) // nowa próba (przed READY) — błędy handshake dotyczą jej, nie zachowanego łącza
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE)) // 30 s
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.RATE_LIMITED)) // 60 s
        runCurrent()
        h.repo.uiAttached(UiSource.CAR) // świadoma akcja: zeruje licznik, nie skraca bieżącego oczekiwania
        runCurrent()
        val before = connects(h)
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(before, connects(h))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(before + 1, connects(h))
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.UNAVAILABLE))
        runCurrent()
        advanceTimeBy(30_001) // bez zerowania byłoby 120 s
        runCurrent()
        assertEquals(before + 2, connects(h))
    }

    @Test
    fun removeClearsBackgroundBlockedProblem() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true), keeper = FakeConnectionKeeper(allowed = false))
        ready(h)
        h.repo.open()
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        h.repo.requestRemoveFromPhone()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertNull(h.state.systemProblem)
    }
}
