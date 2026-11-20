package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia jedenastego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EleventhReviewFixesTest {

    private val qrB = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun switchingFromBleProblemToOtherProblemReconnects() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(0, connects(h))
        h.repo.reportSystemProblem(SystemProblem.CAMERA_PERMISSION_MISSING)
        runCurrent()
        assertEquals(1, connects(h))
    }

    @Test
    fun pairingDropForOtherDeviceReturnsToSessionOfStoredOne() = runTest {
        val deviceA = ByteArray(16) { 0x5a }
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(FakeCredentialStore.present(CredentialState.UNCONFIRMED, deviceId = deviceA)))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(qrB) // QR innego sterownika
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
        assertEquals(listOf("connect", "disconnect", "pair", "connect"), h.link.calls)
    }

    @Test
    fun repeatedNullProblemDoesNotSkipShortRetry() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected) // nieudana próba → odstęp 2 s
        runCurrent()
        h.repo.reportSystemProblem(null) // np. onResume bez wcześniejszego problemu
        runCurrent()
        assertEquals(1, connects(h))
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, connects(h))
    }

    @Test
    fun pairingDoneWithoutSavedGrantFailsRegistration() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.startRegistration(qrB)
        runCurrent()
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(RegistrationState.Failed(UiMessage(MessageId.REGISTRATION_INCOMPLETE)), h.state.registration)
    }

    @Test
    fun completedLinkEventStreamIsTreatedAsLossAndResubscribed() = runTest {
        val h = Harness(backgroundScope)
        h.link.completingSubscriptions = 1
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, h.link.subscribers)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(ConnectionState.READY, h.state.connection)
    }
}
