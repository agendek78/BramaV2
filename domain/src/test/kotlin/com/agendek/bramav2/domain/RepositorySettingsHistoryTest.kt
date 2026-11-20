package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ustawienia nazwy i powiadomienia oraz odczyt historii (app_design §6a). */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositorySettingsHistoryTest {

    private suspend fun TestScope.started(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        runCurrent()
        h.link.calls.clear()
        return h
    }

    @Test
    fun setGateNameIsStoredAndPublished() = runTest {
        val h = started()
        h.repo.setGateName("Wjazd")
        runCurrent()
        assertEquals("Wjazd", h.settings.name.value)
        assertEquals("Wjazd", h.state.gateName)
    }

    @Test
    fun blankGateNameClearsIt() = runTest {
        val h = started()
        h.repo.setGateName("  ")
        runCurrent()
        assertEquals(null, h.settings.name.value)
        assertEquals(null, h.state.gateName)
    }

    @Test
    fun presenceSettingIsPublished() = runTest {
        val h = started()
        assertEquals(true, h.state.presenceNotificationEnabled)
        h.repo.setPresenceNotificationEnabled(false)
        runCurrent()
        assertEquals(false, h.settings.presenceEnabled.value)
        assertEquals(false, h.state.presenceNotificationEnabled)
    }

    @Test
    fun disablingPresenceWhileShownHidesAndDropsReason() = runTest {
        val h = started()
        h.repo.presenceAppeared()
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        h.repo.setPresenceNotificationEnabled(false)
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun appearedIgnoredWhenPresenceDisabled() = runTest {
        val h = started()
        h.repo.setPresenceNotificationEnabled(false)
        runCurrent()
        h.repo.presenceAppeared()
        runCurrent()
        assertEquals(PresenceNotification.HIDDEN, h.state.presence)
        assertTrue("connect" !in h.link.calls)
    }

    @Test
    fun historyFlowExposesStoreEntries() = runTest {
        val h = started()
        val e = HistoryEntry(5L, GateCommand.Close, CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED)
        h.history.published.value = listOf(e)
        assertEquals(listOf(e), h.repo.history.first())
    }
}
