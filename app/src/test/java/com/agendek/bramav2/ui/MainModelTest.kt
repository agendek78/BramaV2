package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.UiMessage
import com.agendek.bramav2.domain.UiMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainModelTest {

    private fun state(
        connection: ConnectionState = ConnectionState.READY,
        credential: CredentialState = CredentialState.ACTIVE,
        controls: Boolean = true,
        suspended: Boolean = false,
    ): GateUiState {
        val s = GateUiState.INITIAL.copy(
            gateName = "Wjazd", connection = connection, credential = credential,
            controlsEnabled = controls, connectSuspended = suspended,
        )
        return s.copy(messages = UiMessages.phone(s))
    }

    @Test
    fun mainShowsConnectionOnly() {
        val ui = mainUi(state())
        assertEquals("Wjazd", ui.title)
        assertEquals(Texts.text(UiMessage(MessageId.CONNECTED)), ui.connection)
    }

    @Test
    fun buttonsFollowControlsEnabled() {
        assertTrue(mainUi(state()).canOpen && mainUi(state()).canClose)
        val off = mainUi(state(controls = false))
        assertFalse(off.canOpen || off.canClose)
    }

    @Test
    fun bondRepairOfferedFromDomainFlag() { // spec 9.5, bond_repair_design §4.3
        assertFalse(mainUi(state()).repairBond)
        val missing = state(connection = ConnectionState.DISCONNECTED, controls = false).copy(bondRepairAvailable = true)
        assertTrue(mainUi(missing).repairBond)
        assertFalse(mainUi(missing.copy(credential = CredentialState.NONE)).repairBond)
        // Instrukcja pod przyciskiem naprawy (17.11.2026): tekst problemu z domeny, tylko gdy naprawa jest oferowana.
        assertEquals(null, mainUi(missing).repairHint)
        val withProblem = missing.copy(handshakeProblem = UiMessage(MessageId.BOND_MISSING))
        assertEquals(Texts.text(UiMessage(MessageId.BOND_MISSING)), mainUi(withProblem).repairHint)
        assertEquals(null, mainUi(withProblem.copy(bondRepairAvailable = false)).repairHint)
    }

    @Test
    fun noCredentialNeedsSetup() {
        val ui = mainUi(state(credential = CredentialState.NONE, controls = false))
        assertTrue(ui.needsSetup)
        assertEquals(ConnectAction.NONE, ui.connectAction)
    }

    @Test
    fun disconnectActionWhenConnected() {
        assertEquals(ConnectAction.DISCONNECT, mainUi(state()).connectAction)
        assertEquals(ConnectAction.CONNECT, mainUi(state(connection = ConnectionState.DISCONNECTED, suspended = true)).connectAction)
    }

    @Test
    fun unconfirmedCredentialOffersConnectWhenIdle() {
        val idle = mainUi(state(credential = CredentialState.UNCONFIRMED, connection = ConnectionState.DISCONNECTED, controls = false))
        assertEquals(ConnectAction.CONNECT, idle.connectAction)
        assertFalse(idle.needsSetup)
        val confirming = mainUi(state(credential = CredentialState.UNCONFIRMED, connection = ConnectionState.AUTHENTICATING, controls = false))
        assertEquals(ConnectAction.NONE, confirming.connectAction)
    }

    @Test
    fun lockedCredentialDoesNotOfferSetup() {
        // Przed odczytem poświadczenia (Keystore niedostępny) stan ma NONE — „Dodaj bramę” nadpisałby poświadczenie.
        val s = GateUiState.INITIAL.copy(systemProblem = com.agendek.bramav2.domain.SystemProblem.CREDENTIAL_LOCKED)
        assertFalse(mainUi(s).needsSetup)
    }

    @Test
    fun defaultTitleWithoutName() {
        assertEquals("Brama", mainUi(GateUiState.INITIAL).title)
    }
}
