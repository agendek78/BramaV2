package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.Messages
import com.agendek.bramav2.domain.SystemProblem

enum class ConnectAction { CONNECT, DISCONNECT, NONE }

/** Model ekranu głównego (app_design §3.1): stan połączenia i duże OTWÓRZ/ZAMKNIJ (bez listy szczegółów, decyzja 17.11.2026). */
data class MainUi(
    val title: String,
    val connection: String,
    val canOpen: Boolean,
    val canClose: Boolean,
    val connectAction: ConnectAction,
    val needsSetup: Boolean,
    /** Ton koloru stanu połączenia (kropka, grafika). */
    val tone: ConnectionTone = ConnectionTone.OFFLINE,
    /** Instrukcja pod przyciskiem naprawy (tekst problemu: BOND_MISSING / REPAIR_BOND_FAILED), gdy domena ją zgłasza. */
    val repairHint: String? = null,
    /** Przycisk „Napraw połączenie” (spec 9.5): brak bondu albo nieudana naprawa. */
    val repairBond: Boolean = false,
)

fun mainUi(s: GateUiState): MainUi {
    val connection = Texts.text(Messages.connection(s.connection))
    val idle = s.connectSuspended || s.connection == ConnectionState.DISCONNECTED || s.connection == ConnectionState.ERROR
    val action = when {
        s.credential == CredentialState.NONE -> ConnectAction.NONE
        // UNCONFIRMED: „Rejestracja niezakończona — połącz się ponownie” wymaga przycisku (domena potwierdza przy Połącz).
        idle -> ConnectAction.CONNECT
        s.credential == CredentialState.ACTIVE -> ConnectAction.DISCONNECT
        else -> ConnectAction.NONE // potwierdzanie rejestracji w toku
    }
    return MainUi(
        title = s.gateName ?: DEFAULT_GATE_NAME,
        connection = connection,
        tone = when (s.connection) {
            ConnectionState.READY -> ConnectionTone.CONNECTED
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> ConnectionTone.OFFLINE
            else -> ConnectionTone.CONNECTING
        },
        canOpen = s.controlsEnabled,
        canClose = s.controlsEnabled,
        connectAction = action,
        // Przy niedostępnym Keystore poświadczenie nie zostało odczytane — „Dodaj bramę” nadpisałby je (spec 9.3).
        needsSetup = s.credential == CredentialState.NONE && s.systemProblem != SystemProblem.CREDENTIAL_LOCKED,
        repairBond = s.bondRepairAvailable && s.credential != CredentialState.NONE,
        repairHint = if (s.bondRepairAvailable && s.credential != CredentialState.NONE) s.handshakeProblem?.let(Texts::text) else null,
    )
}
