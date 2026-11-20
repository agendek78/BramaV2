package com.agendek.bramav2.service

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.LastCommand
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.PresenceNotification
import com.agendek.bramav2.domain.RuleView
import com.agendek.bramav2.domain.UiMessage
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.domain.Texts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationModelTest {

    private val ownMonitoring = RuleSnapshot(1, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0)

    private fun state(
        presence: PresenceNotification = PresenceNotification.SHOWN,
        actions: Boolean = true,
        connection: ConnectionState = ConnectionState.READY,
        rule: RuleSnapshot? = null,
        last: LastCommand? = null,
        background: Boolean = false,
    ) = GateUiState.INITIAL.copy(
        gateName = "Wjazd", presence = presence, notificationActionsAvailable = actions, connection = connection,
        rule = RuleView(rule, fresh = rule != null, refreshing = false), lastCommand = last, backgroundLinkNeeded = background,
    )

    @Test
    fun actionsOnlyWhenAvailable() {
        assertTrue(gateNotification(uiVisible = false, s = state())!!.showActions)
        assertFalse(gateNotification(uiVisible = false, s = state(actions = false))!!.showActions)
        // QUIET z łączem w tle (np. ponowne uzbrojenie przez OPEN(true), spec 4.3 pkt 5): powiadomienie reguły bez przycisków.
        assertFalse(gateNotification(uiVisible = false, s = state(presence = PresenceNotification.QUIET, actions = true, rule = ownMonitoring, background = true))!!.showActions)
    }

    @Test
    fun quietWithSuppressedRuleHasNoNotification() {
        // Spec 4.3 pkt 4–5: po wyciszeniu własna reguła nie utrzymuje łącza — domena nie zgłasza potrzeby łącza w tle.
        assertNull(gateNotification(uiVisible = false, s = state(presence = PresenceNotification.QUIET, rule = ownMonitoring, background = false)))
    }

    @Test
    fun notificationHasOnlyConnectionLine() {
        // Decyzja właściciela 17.11.2026: tylko stan połączenia (bez pozycji, reguły i ostrzeżenia o uzbrojeniu).
        val n = gateNotification(uiVisible = false, s = state(rule = ownMonitoring))!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.CONNECTED))), n.lines)
        assertEquals("Wjazd", n.title)
    }

    @Test
    fun ruleOnlyNotificationHasNoActions() {
        val n = gateNotification(uiVisible = false, s = state(presence = PresenceNotification.HIDDEN, actions = false, rule = ownMonitoring, background = true))!!
        assertFalse(n.showActions)
        assertEquals(1, n.lines.size)
    }

    @Test
    fun noNotificationWhenServiceNotNeeded() {
        assertNull(gateNotification(uiVisible = false, s = state(presence = PresenceNotification.HIDDEN, actions = false)))
    }

    @Test
    fun serviceNeededWhileShown() {
        assertTrue(serviceNeeded(uiVisible = false, s = state()))
    }

    @Test
    fun serviceNeededWhileCommandInFlight() {
        val s = state(presence = PresenceNotification.HIDDEN, last = LastCommand(GateCommand.Close, CommandOutcome.IN_FLIGHT, null))
        assertTrue(serviceNeeded(s, uiVisible = false))
    }

    @Test
    fun serviceFollowsDomainBackgroundNeed() {
        // Chwilowe DISCONNECTED przy ponownym łączeniu nie zatrzymuje usługi — decyduje domena.
        assertTrue(serviceNeeded(uiVisible = true, s = state(presence = PresenceNotification.HIDDEN, rule = ownMonitoring, connection = ConnectionState.DISCONNECTED, background = true)))
        assertFalse(serviceNeeded(uiVisible = false, s = state(presence = PresenceNotification.HIDDEN, rule = ownMonitoring, background = false)))
    }

    @Test
    fun serviceNotNeededWhenIdleAndDisarmed() {
        val disarmed = RuleSnapshot(1, AutoClosePhase.DISARMED, OwnerRelation.NONE, RuleReason.NONE, 0)
        assertFalse(serviceNeeded(uiVisible = false, s = state(presence = PresenceNotification.HIDDEN, rule = disarmed)))
    }

    @Test
    fun commandInFlightOnScreenDoesNotNeedService() {
        val s = state(presence = PresenceNotification.HIDDEN, last = LastCommand(GateCommand.Close, CommandOutcome.IN_FLIGHT, null))
        assertFalse(serviceNeeded(s, uiVisible = true))
    }

    @Test
    fun firmwareTransferInBackgroundShowsProgress() {
        // Spec 15.5: transfer działa w usłudze po wyjściu z ekranu — domena zgłasza łącze w tle.
        val s = state(presence = PresenceNotification.HIDDEN, actions = false, background = true)
            .copy(firmwareUpdate = com.agendek.bramav2.domain.FirmwareUpdate.Transferring(42))
        val n = gateNotification(s, uiVisible = false)!!
        assertEquals(1, n.lines.size) // postęp firmware nie jest w powiadomieniu (decyzja 17.11.2026)
        assertFalse(n.showActions)
    }

    @Test
    fun notificationHasNoProblemTextsAndNoActions() {
        val s = state(actions = false, connection = ConnectionState.DISCONNECTED).copy(
            systemProblem = com.agendek.bramav2.domain.SystemProblem.BLUETOOTH_OFF,
            handshakeProblem = UiMessage(MessageId.REJECT_WINDOW_CLOSED),
        )
        val n = gateNotification(s, uiVisible = false)!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.NOT_CONNECTED))), n.lines)
        assertFalse(n.showActions)
    }
}
