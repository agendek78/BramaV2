package com.agendek.bramav2.service

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.Messages
import com.agendek.bramav2.domain.PresenceNotification
import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import com.agendek.bramav2.domain.Texts

/** Treść powiadomienia usługi połączenia: tytuł (nazwa bramy), jedna linia ze stanem połączenia i przyciski OTWÓRZ/ZAMKNIJ. */
data class GateNotification(val title: String, val lines: List<String>, val showActions: Boolean)

/**
 * `null`: usługa niepotrzebna, brak powiadomienia. Decyzja właściciela (17.11.2026): w powiadomieniu wyłącznie stan połączenia
 * i przyciski, bez pozycji, reguły, wyniku komendy, postępu firmware, problemów i ostrzeżenia o uzbrojeniu przy rozłączeniu.
 */
fun gateNotification(s: GateUiState, uiVisible: Boolean): GateNotification? {
    if (!serviceNeeded(s, uiVisible)) return null
    val shown = s.presence == PresenceNotification.SHOWN
    val lines = listOf(Texts.text(Messages.connection(s.connection)))
    return GateNotification(title = s.gateName ?: DEFAULT_GATE_NAME, lines = lines, showActions = shown && s.notificationActionsAvailable)
}

/**
 * Usługa `connectedDevice` potrzebna (app_design §4): powiadomienie w zasięgu, łącze potrzebne poza ekranem według
 * domeny (m.in. własna uzbrojona reguła, także w trakcie ponownego łączenia) albo komenda w toku po zamknięciu ekranu.
 */
fun serviceNeeded(s: GateUiState, uiVisible: Boolean): Boolean =
    s.presence == PresenceNotification.SHOWN ||
        s.backgroundLinkNeeded ||
        (!uiVisible && s.lastCommand?.outcome == CommandOutcome.IN_FLIGHT)
