package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** `backgroundLinkNeeded` dla usługi `connectedDevice` w `:app` (app_design §4). */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundLinkTest {

    private suspend fun TestScope.ready(phase: AutoClosePhase): Harness {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(phase)))
        runCurrent()
        return h
    }

    @Test
    fun phoneUiAloneDoesNotNeedBackgroundLink() = runTest {
        val h = ready(DISARMED)
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun ownArmedRuleNeedsLinkAlsoWhileReconnecting() = runTest {
        val h = ready(MONITORING)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue(h.state.backgroundLinkNeeded)
        h.link.emit(LinkEvent.Disconnected) // utrata BLE: chwilowe DISCONNECTED, potem ponowne łączenie
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
        assertTrue(h.state.backgroundLinkNeeded)
    }

    @Test
    fun userDisconnectEndsBackgroundNeed() = runTest {
        val h = ready(MONITORING)
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        h.repo.confirmDisconnect(UiSource.PHONE)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun bluetoothBrieflyOffKeepsBackgroundNeedAndReconnects() = runTest {
        val h = ready(MONITORING)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
        assertTrue(h.state.backgroundLinkNeeded) // usługa zostaje: powrót przed auto-CLOSE (14.2)
        h.link.calls.clear()
        advanceTimeBy(20_000)
        h.repo.reportSystemProblem(null) // Bluetooth włączony w oknie
        runCurrent()
        assertTrue("connect" in h.link.calls)
    }

    @Test
    fun bluetoothOffBeyondWindowExpires() = runTest {
        val h = ready(MONITORING)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun missingBluetoothPermissionEndsBackgroundNeed() = runTest {
        val h = ready(MONITORING)
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_PERMISSION_MISSING)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded) // usługa connectedDevice i tak nie wystartuje
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    /** Właściciel uzbrojonej reguły odjeżdża: łącze utracone, ekran zamknięty. */
    private suspend fun TestScope.ownerLeft(): Harness {
        val h = ready(MONITORING)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        return h
    }

    @Test
    fun staleOwnArmedRuleExpiresAfterOwnerLossWindow() = runTest {
        val h = ownerLeft()
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(h.state.backgroundLinkNeeded) // sterownik mógł jeszcze nie zamknąć: telefon próbuje wrócić
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
        val before = connects(h)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(before, connects(h)) // bez skanowania w nieskończoność
    }

    @Test
    fun failedRetriesDoNotExtendExpiry() = runTest {
        val h = ownerLeft()
        repeat(5) {
            advanceTimeBy(10_000)
            runCurrent()
            h.link.emit(LinkEvent.Disconnected) // nieudana próba ponownego połączenia
            runCurrent()
        }
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun freshSnapshotRestoresOwnArmedReason() = runTest {
        val h = ownerLeft()
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
        h.repo.uiAttached(UiSource.PHONE) // powrót: ekran łączy, sesja READY z regułą nadal uzbrojoną
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(MONITORING, revision = 2)))
        runCurrent()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue(h.state.backgroundLinkNeeded)
    }

    @Test
    fun cachedOwnArmedRuleAfterProcessRestartExpiresToo() = runTest {
        val h = Harness(backgroundScope, ruleCache = FakeRuleCacheStore(LastKnownRule(snapshot(MONITORING), 0L)))
        h.repo.start()
        runCurrent()
        assertTrue(h.state.backgroundLinkNeeded)
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun removeFromPhoneKeepsPresenceSetting() = runTest {
        val settings = FakeSettingsStore()
        settings.presenceEnabled.value = false
        val h = Harness(backgroundScope, settings = settings)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertFalse(h.state.presenceNotificationEnabled)
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(CredentialState.NONE, h.state.credential)
        assertFalse(h.state.presenceNotificationEnabled) // DataStore nie wyśle ponownie `false`
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
    }

    @Test
    fun confirmingRegistrationDoesNotNeedBackgroundService() = runTest {
        val qr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(qr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals(RegistrationState.ConfirmingSession, h.state.registration)
        assertEquals("connect", h.link.calls.last()) // domena potwierdza, dopóki proces żyje
        assertFalse(h.state.backgroundLinkNeeded) // ale nie trzyma usługi: brak limitu prób
    }

    @Test
    fun coldStartFromPresenceRespectsDisabledSetting() = runTest {
        val settings = FakeSettingsStore()
        settings.presenceEnabled.value = false
        val h = Harness(backgroundScope, settings = settings)
        h.repo.start()
        h.repo.presenceAppeared() // CDM budzi proces: zdarzenie przed wiadomością z DataStore
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertFalse("connect" in h.link.calls) // bez krótkiego powiadomienia i połączenia
    }
}
