package com.agendek.bramav2.ble.core

/** Dostępność rejestracji PIN-em ogłaszana przez sterownik w reklamie, gdy okno rejestracji jest otwarte. */
sealed interface PinAvailability {
    /** Brak danych: sterownik poza zasięgiem, okno zamknięte albo starsze firmware bez pola. Rejestracji nie blokuje. */
    data object Unknown : PinAvailability

    /** Rejestracja PIN-em możliwa; [attemptsLeft] = pozostałe nieudane potwierdzenia PIN-u w oknie (1..5). */
    data class Available(val attemptsLeft: Int) : PinAvailability

    /** Rejestracja PIN-em teraz niemożliwa (limit prób, np. po restarcie bramy): potrzebny przycisk albo QR. */
    data object Unavailable : PinAvailability
}

/**
 * Pole producenta w reklamie sterownika (firmware `set_adv_data`): identyfikator firmy 0xFFFF (LE, w danych
 * zwracanych przez Androida jako klucz) i 1. bajt z liczbą pozostałych prób PIN-u (0..5), kolejne bajty ignorowane. Poza oknem pola brak.
 */
object GateAdvertisement {
    const val COMPANY_ID = 0xFFFF
    private const val MAX_ATTEMPTS = 5

    /** [manufacturerData]: dane producenta [COMPANY_ID] bez identyfikatora firmy; `null`, gdy pola brak. */
    fun pinAvailability(manufacturerData: ByteArray?): PinAvailability {
        // Pierwszy bajt to liczba prób; dalsze bajty (przyszłe rozszerzenia pola) są ignorowane, żeby stara aplikacja dalej blokowała.
        val left = manufacturerData?.takeIf { it.isNotEmpty() }?.get(0)?.toInt() ?: return PinAvailability.Unknown
        return when (left) {
            0 -> PinAvailability.Unavailable
            in 1..MAX_ATTEMPTS -> PinAvailability.Available(left)
            else -> PinAvailability.Unknown
        }
    }

    /**
     * Jedna dostępność z reklam kilku bramek w zasięgu (kreator nie zna jeszcze adresu docelowej). Bramki bez pola
     * ([PinAvailability.Unknown]) nie wpływają na wynik; blokada tylko wtedy, gdy wszystkie bramki z polem mają 0 prób,
     * żeby sąsiednia bramka z wyczerpanym licznikiem nie blokowała rejestracji na własnej.
     */
    fun aggregate(states: Collection<PinAvailability>): PinAvailability {
        val withField = states.filter { it !is PinAvailability.Unknown }
        if (withField.isEmpty()) return PinAvailability.Unknown
        val best = withField.filterIsInstance<PinAvailability.Available>().maxOfOrNull { it.attemptsLeft }
        return if (best != null) PinAvailability.Available(best) else PinAvailability.Unavailable
    }
}
