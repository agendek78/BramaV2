package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.SessionRequest
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

/** Powiadomienie „brama w zasięgu” (spec 4.3, pin_presence_design §5.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceNotificationTest {

    private val QR = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.started(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private suspend fun TestScope.shownAndReady(h: Harness = Harness(backgroundScope), s: com.agendek.bramav2.protocol.RuleSnapshot = snapshot(DISARMED)): Harness {
        started(h)
        h.repo.presenceAppeared()
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun ack(request: SessionRequest, s: com.agendek.bramav2.protocol.RuleSnapshot) = CommandAck(
        if (request is SessionRequest.Open) MessageType.OPEN.code else MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 1, s,
    )

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun appearedShowsAndConnects() = runTest {
        val h = started()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.SHOWN, h.state.presence)
        assertEquals(listOf("connect"), h.link.calls)
    }

    @Test
    fun appearedLiftsUserSuspension() = runTest {
        val h = started()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue(h.state.connectSuspended)
        h.link.calls.clear()
        h.repo.presenceAppeared()
        runCurrent()
        assertFalse(h.state.connectSuspended)
        assertEquals(listOf("connect"), h.link.calls)
    }

    @Test
    fun appearedKeepsTerminalBlock() = runTest {
        val h = started()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeRejected(HandshakeRejectReason.AUTH_FAILED))
        runCurrent()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.link.calls.clear()
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.SHOWN, h.state.presence)
        assertTrue("connect" !in h.link.calls)
        assertFalse(h.state.notificationActionsAvailable)
    }

    @Test
    fun actionsOnlyWhenReadyAndIdle() = runTest {
        val h = started()
        h.repo.presenceAppeared()
        runCurrent()
        assertFalse(h.state.notificationActionsAvailable)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertTrue(h.state.notificationActionsAvailable)
        h.repo.notificationClose()
        runCurrent()
        assertFalse(h.state.notificationActionsAvailable)
    }

    @Test
    fun notificationOpenUsesDefaultAutoClose() = runTest {
        val h = shownAndReady(Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true)))
        h.repo.notificationOpen()
        runCurrent()
        assertEquals(listOf("send:OPEN(true)"), h.link.calls.filter { it.startsWith("send:") })
    }

    @Test
    fun notificationCloseSendsClose() = runTest {
        val h = shownAndReady()
        h.repo.notificationClose()
        runCurrent()
        assertEquals(listOf("send:CLOSE"), h.link.calls.filter { it.startsWith("send:") })
    }

    @Test
    fun dismissedInShownGoesQuietAndDisconnects() = runTest {
        val h = shownAndReady()
        wait(600_000) // brak licznika czasu: samo trwanie w zasięgu nie wycisza
        assertEquals(PresenceNotification.SHOWN, h.state.presence)
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("disconnect" in h.link.calls)
        assertTrue(h.presence.saved.last().quiet)
    }

    @Test
    fun dismissedOutsideShownIsIgnored() = runTest {
        val h = started()
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        h.repo.presenceAppeared()
        runCurrent()
        h.repo.notificationDismissed()
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
    }

    @Test
    fun dismissedKeepsLinkForPendingCommand() = runTest {
        val h = shownAndReady()
        h.repo.notificationClose()
        runCurrent()
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("disconnect" !in h.link.calls) // komenda w toku trzyma łącze
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, ack(SessionRequest.Close, snapshot(DISARMED)), true))
        runCurrent()
        assertEquals(CommandOutcome.OUTPUT_TRIGGERED, h.state.lastCommand?.outcome)
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun quietDisconnectsDespiteOwnArmedRule() = runTest {
        val h = shownAndReady(s = snapshot(MONITORING))
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("disconnect" in h.link.calls)
        h.link.calls.clear()
        wait(60_000)
        assertTrue("connect" !in h.link.calls)
    }

    @Test
    fun quietKeepsLinkWhilePhoneUiAttached() = runTest {
        val h = shownAndReady()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("disconnect" !in h.link.calls)
    }

    @Test
    fun quietAppearedAgainStaysQuiet() = runTest {
        val h = shownAndReady()
        h.repo.notificationDismissed()
        runCurrent()
        h.link.calls.clear()
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("connect" !in h.link.calls)
    }

    @Test
    fun disappearedResetsToHidden() = runTest {
        val h = shownAndReady(s = snapshot(MONITORING, remainingMs = 3_600_000))
        h.repo.notificationDismissed()
        runCurrent()
        h.link.calls.clear()
        h.repo.presenceDisappeared()
        runCurrent()
        wait(30_000) // karencja na chwilową utratę reklam
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertFalse(h.presence.saved.last().quiet)
        wait(60_000)
        assertTrue("connect" in h.link.calls) // wstrzymanie zdjęte: własna reguła znów jest powodem połączenia
    }

    @Test
    fun briefDisappearAfterQuietKeepsQuiet() = runTest {
        val h = shownAndReady(s = snapshot(MONITORING))
        h.repo.notificationDismissed()
        runCurrent()
        h.link.calls.clear()
        h.repo.presenceDisappeared() // echo rozłączenia albo chwilowa utrata reklam przez skan CDM
        runCurrent()
        wait(20_000)
        h.repo.presenceAppeared()
        runCurrent()
        wait(60_000) // karencja anulowana pojawieniem się
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("connect" !in h.link.calls)
        h.repo.presenceDisappeared()
        runCurrent()
        wait(29_000) // nowa karencja liczy się od nowa
        assertEquals(PresenceNotification.QUIET, h.state.presence)
    }

    @Test
    fun absenceLongerThanGraceResetsAndNextAppearShows() = runTest {
        val h = shownAndReady(s = snapshot(MONITORING))
        h.repo.notificationDismissed()
        runCurrent()
        h.repo.presenceDisappeared()
        runCurrent()
        wait(30_000)
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.SHOWN, h.state.presence)
    }

    @Test
    fun openTrueInQuietLiftsSuppression() = runTest {
        val h = shownAndReady(Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true)))
        h.repo.notificationDismissed()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Open(true), ack(SessionRequest.Open(true), snapshot(MONITORING, revision = 2)), true))
        runCurrent()
        h.link.calls.clear()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertFalse(h.presence.saved.last().ruleSuppressed)
    }

    @Test
    fun presenceEventsOutOfOrderAreIgnored() = runTest {
        val h = started()
        h.repo.presenceDisappeared()
        h.repo.notificationOpen()
        h.repo.notificationClose()
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertTrue(h.link.calls.isEmpty())
        h.repo.presenceAppeared()
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(listOf("connect"), h.link.calls)
        h.repo.notificationDismissed()
        runCurrent()
        h.link.calls.clear()
        h.repo.notificationOpen()
        h.repo.notificationClose()
        runCurrent()
        assertTrue(h.link.calls.none { it.startsWith("send:") })
    }

    @Test
    fun quietSurvivesProcessRestart() = runTest {
        val h = Harness(
            backgroundScope,
            ruleCache = FakeRuleCacheStore(LastKnownRule(snapshot(MONITORING), 1)),
            presence = FakePresenceStore(PresenceRecord(quiet = true, ruleSuppressed = true)),
        )
        h.repo.start()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("connect" !in h.link.calls)
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue("connect" !in h.link.calls)
    }

    @Test
    fun quietIsPersistedAndClearedOnDisappear() = runTest {
        val h = shownAndReady()
        h.repo.notificationDismissed()
        runCurrent()
        assertEquals(PresenceRecord(quiet = true, ruleSuppressed = true), h.presence.saved.last())
        h.repo.presenceDisappeared()
        runCurrent()
        wait(30_000)
        assertEquals(PresenceRecord(quiet = false, ruleSuppressed = false), h.presence.saved.last())
    }

    @Test
    fun removeFromPhoneWhileQuietClearsPresence() = runTest {
        val h = shownAndReady(s = snapshot(MONITORING))
        h.repo.notificationDismissed()
        runCurrent()
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertEquals(PresenceRecord(quiet = false, ruleSuppressed = false), h.presence.saved.last())
    }

    @Test
    fun removeFromPhoneWhileShownDropsPresenceReason() = runTest {
        val h = shownAndReady()
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        // Ponowna rejestracja z ekranu, potem ekran zamknięty: bez powiadomienia nie ma powodu trzymać łącza.
        h.repo.uiAttached(UiSource.PHONE)
        h.repo.startRegistration(QR)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        runCurrent()
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(CredentialState.ACTIVE, h.state.credential)
        h.link.calls.clear()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun appearedSkipsPendingShortRetry() = runTest {
        val h = started()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.HandshakeFailed) // nieudana próba przed READY → krótki odstęp ponowienia
        runCurrent()
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.link.calls.clear()
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(listOf("connect"), h.link.calls) // od razu, bez czekania na odstęp (spec 4.3 pkt 2)
    }
}
