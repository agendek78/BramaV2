package com.agendek.bramav2.car

import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.RuleView
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.UiMessage
import com.agendek.bramav2.domain.UiMessages
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPaneTest {

    private val monitoring = RuleSnapshot(1, AutoClosePhase.MONITORING, OwnerRelation.SELF, RuleReason.NONE, 0)

    /** Jak opublikowany stan repozytorium: `carMessages` wyliczone z reszty stanu. */
    private fun state(credential: CredentialState = CredentialState.ACTIVE, controls: Boolean = true): GateUiState {
        val s = GateUiState.INITIAL.copy(
            gateName = "Wjazd", credential = credential, connection = ConnectionState.READY, controlsEnabled = controls,
            rule = RuleView(monitoring, fresh = true, refreshing = false),
        )
        return s.copy(carMessages = UiMessages.car(s))
    }

    private val connected = Texts.text(UiMessage(MessageId.CONNECTED))

    @Test
    fun paneShowsOnlyTitleAndConnectionState() {
        val p = carPane(state())
        assertEquals("Wjazd", p.title)
        assertEquals(connected, p.status)
        assertEquals(CarTone.CONNECTED, p.tone)
    }

    @Test
    fun noStatusTextsBeyondConnection() {
        val p = carPane(state())
        val all = listOf(p.title, p.status).joinToString(" ")
        assertFalse("Położenie" in all)
        assertFalse(Texts.text(UiMessage(MessageId.RULE_MONITORING_SELF)) in all)
        assertFalse(Texts.text(UiMessage(MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_OFF)) in all)
    }

    @Test
    fun configureOnPhoneHasNoActions() {
        val p = carPane(state(credential = CredentialState.NONE, controls = false))
        assertFalse(p.showActions)
        assertEquals(Texts.text(UiMessage(MessageId.CONFIGURE_ON_PHONE)), p.status)
    }

    @Test
    fun actionsFollowControlsEnabled() {
        val on = carPane(state())
        assertTrue(on.showActions && on.canOpen && on.canClose)
        val off = carPane(state(controls = false))
        assertTrue(off.showActions)
        assertFalse(off.canOpen || off.canClose)
    }

    @Test
    fun toneFollowsConnection() {
        fun tone(c: ConnectionState) = carPane(state().copy(connection = c)).tone
        assertEquals(CarTone.CONNECTED, tone(ConnectionState.READY))
        assertEquals(CarTone.OFFLINE, tone(ConnectionState.DISCONNECTED))
        assertEquals(CarTone.OFFLINE, tone(ConnectionState.ERROR))
        assertEquals(CarTone.CONNECTING, tone(ConnectionState.CONNECTING))
    }

    @Test
    fun defaultTitleWithoutName() {
        assertEquals("Brama", carPane(GateUiState.INITIAL).title)
    }

    @Test
    fun paneUsesPublishedCarMessages() {
        val s = state().copy(carMessages = listOf(UiMessage(MessageId.NOT_CONNECTED), UiMessage(MessageId.POSITION_UNKNOWN)))
        assertEquals(Texts.text(UiMessage(MessageId.NOT_CONNECTED)), carPane(s).status)
    }
}
