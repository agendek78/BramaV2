package com.agendek.bramav2.car

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.LastCommand
import com.agendek.bramav2.domain.PresenceNotification
import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.UiMessages
import kotlin.random.Random

/**
 * Treść powiadomienia w samochodzie (spec 5.4) — wyłącznie teksty z listy zamkniętej.
 * [alert]: pierwsze pokazanie w pobycie albo pierwsze pojawienie się przycisku OTWÓRZ — powiadomienie ma zaalarmować.
 * [cycle]: numer pobytu przy bramie (odrzucenie z poprzedniego pobytu jest ignorowane).
 */
data class CarNotification(val title: String, val lines: List<String>, val showOpen: Boolean, val alert: Boolean, val cycle: Int)

/**
 * Kiedy pokazać powiadomienie w samochodzie (spec 5.4). Cykl = jeden pobyt przy bramie (stan SHOWN powiadomienia
 * z 4.3). Na początku cyklu zapamiętuje bieżące `lastCommand` (tożsamość obiektu), aby wynik z poprzedniego pobytu
 * nie ukrył nowego powiadomienia. Numery pobytów startują od [firstCycle], losowego w każdym procesie, aby
 * „odrzucono” z powiadomienia poprzedniego procesu nie trafiło w nowy pobyt. Nie jest bezpieczna wątkowo.
 */
class CarNotificationPolicy(firstCycle: Int = Random.nextInt(0, Int.MAX_VALUE / 2)) {
    private var inCycle = false
    private var cycleStart: LastCommand? = null
    private var closed = false
    private var cycle = firstCycle
    private var announced = false
    private var buttonAnnounced = false

    fun update(s: GateUiState, projecting: Boolean): CarNotification? {
        if (s.presence != PresenceNotification.SHOWN) {
            inCycle = false
            return null
        }
        if (!inCycle) {
            inCycle = true
            cycle++
            cycleStart = s.lastCommand
            closed = false
            announced = false
            buttonAnnounced = false
        }
        val last = s.lastCommand?.takeIf { it !== cycleStart }
        if (last != null && last.command is GateCommand.Open && last.outcome == CommandOutcome.OUTPUT_TRIGGERED) closed = true
        if (closed || !projecting) return null
        val lines = UiMessages.carNotification(s).map(Texts::car)
        val showOpen = s.notificationActionsAvailable && !UiMessages.carNeedsPhoneSetup(s)
        // Pierwsze pokazanie i pierwszy przycisk w pobycie alarmują (heads-up); dalsze zmiany tylko aktualizują treść.
        val alert = !announced || (showOpen && !buttonAnnounced)
        announced = true
        if (showOpen) buttonAnnounced = true
        return CarNotification(s.gateName ?: DEFAULT_GATE_NAME, lines, showOpen, alert, cycle)
    }

    /**
     * Powiadomienie pobytu [cycle] odrzucone (w aucie lub na telefonie) — ukryte do końca tego pobytu.
     * @return `true`, gdy zamknęło bieżący pobyt; odrzucenie z innego pobytu jest ignorowane.
     */
    fun onDismissedByDriver(cycle: Int): Boolean {
        if (!inCycle || cycle != this.cycle || closed) return false
        closed = true
        return true
    }
}
