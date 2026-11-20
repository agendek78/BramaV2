package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia dziesiątego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class TenthReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun noConnectionAttemptsWhileBluetoothUnavailable() = runTest {
        for (problem in listOf(SystemProblem.BLUETOOTH_OFF, SystemProblem.BLUETOOTH_PERMISSION_MISSING)) {
            val h = Harness(backgroundScope)
            h.repo.start()
            h.repo.reportSystemProblem(problem)
            h.repo.uiAttached(UiSource.PHONE)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(problem.name, 0, connects(h))
            h.repo.reportSystemProblem(null)
            runCurrent()
            assertEquals(problem.name, 1, connects(h))
        }
    }

    @Test
    fun lostLinkIsEndedBeforeNewAttempt() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        // `disconnect()` przed nową próbą: kontrakt GateLink odcina spóźnione zdarzenia starego połączenia
        // (odróżnienie połączeń należy do `:ble`, domena nie zna ich identyfikatorów).
        assertEquals(listOf("disconnect", "connect"), h.link.calls)
    }

    @Test
    fun nonceFailureShowsRegistrationIncomplete() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.nonces.fails = true
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
        assertTrue("pair" !in h.link.calls)
    }

    @Test
    fun linkEventStreamFailureIsRecovered() = runTest {
        val h = Harness(backgroundScope)
        h.link.failingSubscriptions = 1
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(ConnectionState.READY, h.state.connection)
    }

    @Test
    fun settingsStreamFailureIsRecovered() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true, failingSubscriptions = 1))
        h.repo.start()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(true, h.state.nextOpenAutoClose)
    }
}
