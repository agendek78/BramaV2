package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.HistoryEntry

/** Nazwa komendy w historii (etykieta, poza listą zamkniętą). */
fun historyTitle(e: HistoryEntry): String = when (val c = e.command) {
    is GateCommand.Open -> if (c.autoCloseOnDisconnect) "OTWÓRZ (z automatycznym zamknięciem)" else "OTWÓRZ"
    GateCommand.Close -> "ZAMKNIJ"
}
