package com.agendek.bramav2.domain

/**
 * Komunikaty w kolejności ważności spec 5.2: (1) połączenie i „Położenie nieznane”, (2) reguła,
 * (3) wynik ostatniej komendy, (4) ustawienie następnego otwarcia. Nazwa bramy jest w `gateName`.
 * Telefon pokazuje problem systemowy jako pierwszy (przyczyna braku połączenia); samochód przycina listę
 * od końca, więc tam pozycje (1)–(2) są zawsze na początku, a problemy dalej.
 */
object UiMessages {

    fun phone(state: GateUiState): List<UiMessage> = buildList {
        state.systemProblem?.let { add(Messages.systemProblem(it)) }
        add(Messages.connection(state.connection))
        state.handshakeProblem?.let(::add)
        add(UiMessage(MessageId.POSITION_UNKNOWN))
        add(Messages.rule(state.rule))
        state.lastCommand?.let(Messages::commandResult)?.let(::add)
        Messages.firmwareUpdate(state.firmwareUpdate)?.let(::add)
        add(Messages.nextOpenLabel(state.nextOpenAutoClose))
    }

    /** Spec 5.2: brak konfiguracji lub uprawnień → wyłącznie „Skonfiguruj aplikację na telefonie po zatrzymaniu”. */
    fun car(state: GateUiState): List<UiMessage> {
        configureOnPhone(state)?.let { return it }
        // (1) połączenie i położenie, (2) reguła muszą przetrwać przycinanie od końca; problemy dalej.
        return buildList {
            add(Messages.connection(state.connection))
            add(UiMessage(MessageId.POSITION_UNKNOWN))
            add(Messages.rule(state.rule))
            state.systemProblem?.takeIf { it !in PHONE_ONLY_PROBLEMS }?.let { add(Messages.systemProblem(it)) }
            state.handshakeProblem?.let(::add)
            state.lastCommand?.let(Messages::commandResult)?.let(::add)
            add(Messages.nextOpenLabel(state.nextOpenAutoClose))
        }
    }

    /**
     * Powiadomienie w samochodzie (spec 5.4, decyzja 19.11.2026): jak powiadomienie telefonu — jedna linia ze stanem
     * połączenia; przy braku konfiguracji tylko „Skonfiguruj…”. Bez położenia, problemów i wyniku komendy.
     */
    fun carNotification(state: GateUiState): List<UiMessage> =
        configureOnPhone(state)?.take(1) ?: listOf(Messages.connection(state.connection))

    /** Samochód wymaga konfiguracji na telefonie (5.2, 5.4) — wtedy bez przycisków sterowania. */
    fun carNeedsPhoneSetup(state: GateUiState): Boolean = configureOnPhone(state) != null

    /** Wspólna reguła ekranu i powiadomienia w samochodzie (5.2, 5.4): bez konfiguracji tylko „Skonfiguruj…”. */
    private fun configureOnPhone(state: GateUiState): List<UiMessage>? =
        if (state.credential != CredentialState.ACTIVE || state.systemProblem in CONFIGURE_ON_PHONE_PROBLEMS) {
            listOf(UiMessage(MessageId.CONFIGURE_ON_PHONE), UiMessage(MessageId.POSITION_UNKNOWN))
        } else {
            null
        }

    private val PHONE_ONLY_PROBLEMS = setOf(SystemProblem.CAMERA_PERMISSION_MISSING)

    private val CONFIGURE_ON_PHONE_PROBLEMS =
        setOf(SystemProblem.BLUETOOTH_PERMISSION_MISSING, SystemProblem.CREDENTIAL_LOCKED)
}
