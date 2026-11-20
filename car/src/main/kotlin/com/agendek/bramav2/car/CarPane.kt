package com.agendek.bramav2.car

import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.Texts

/** Ton stanu połączenia (kolor kropki, jak `ConnectionTone` na telefonie). */
enum class CarTone { CONNECTED, CONNECTING, OFFLINE }

/**
 * Model ekranu samochodowego (spec 5.2, decyzja 19.11.2026): tytuł z nazwą bramy, jedna linia stanu połączenia z kropką
 * i przyciski — jak na ekranie głównym telefonu, bez pozostałych tekstów statusów. Przy braku konfiguracji w linii
 * stoi „Skonfiguruj aplikację na telefonie po zatrzymaniu”, bez przycisków.
 */
data class CarPane(
    val title: String,
    val status: String,
    val tone: CarTone,
    val showActions: Boolean,
    val canOpen: Boolean,
    val canClose: Boolean,
)

fun carPane(s: GateUiState): CarPane {
    val first = s.carMessages.firstOrNull() // `UiMessages.car`: „Skonfiguruj…” albo stan połączenia
    val configure = first?.id == MessageId.CONFIGURE_ON_PHONE
    return CarPane(
        title = s.gateName ?: DEFAULT_GATE_NAME,
        status = first?.let(Texts::car).orEmpty(),
        tone = when (s.connection) {
            ConnectionState.READY -> CarTone.CONNECTED
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> CarTone.OFFLINE
            else -> CarTone.CONNECTING
        },
        showActions = !configure,
        canOpen = !configure && s.controlsEnabled,
        canClose = !configure && s.controlsEnabled,
    )
}
