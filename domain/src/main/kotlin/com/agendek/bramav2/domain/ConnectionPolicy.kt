package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot

/** Start po zaniku zasilania lub nieważnej kopii RTC: autozamykanie mogło zostać wyłączone (spec 12.2, 14.5). */
fun RuleSnapshot.isRebootDisarmed(): Boolean = phase == AutoClosePhase.DISARMED && reason == RuleReason.REBOOT

/**
 * Własna uzbrojona reguła, dla której łącze właściciela musi trwać (spec 4.2). BLOCKED zachowuje właściciela
 * i uzbrojenie (14.4): po zniknięciu blokady obecny właściciel wraca do MONITORING bez CLOSE, nieobecny dostaje
 * WAITING 30 s. FAULT nie ma autozamykania, więc nie jest powodem.
 */
fun RuleSnapshot.isOwnArmed(): Boolean =
    ownerRelation == OwnerRelation.SELF && (
        phase == AutoClosePhase.MONITORING || phase == AutoClosePhase.WAITING ||
            phase == AutoClosePhase.BOOT_WAIT || phase == AutoClosePhase.BLOCKED
        )

/**
 * Kiedy łącze ma istnieć (domain_design §6.2). Powody: podpięte UI telefonu/samochodu albo własna uzbrojona reguła.
 * „Rozłącz” wstrzymuje wszystkie powody do świadomego „Połącz”; odmowy 12.4 sterują backoffem i blokadą ponowień.
 * Nie jest bezpieczna wątkowo — używana wyłącznie z aktora [GateRepository].
 */
class ConnectionPolicy {
    private val attached = mutableSetOf<UiSource>()
    private var ownerArmed = false
    private var confirming = false
    private var commandPending = false
    private var terminalBlocked = false
    private var presence = false
    private var ownerArmedSuppressed = false
    private var ownerArmedExpired = false
    private var updating = false
    private var nextBackoffMs = INITIAL_BACKOFF_MS

    var suspended: Boolean = false
        private set

    /**
     * Czy otwierać łącze: podpięte UI, powiadomienie „w zasięgu”, własna uzbrojona reguła (o ile nie wstrzymana
     * wyciszeniem powiadomienia, spec 4.3), oczekujące potwierdzenie rejestracji albo aktualizacja firmware.
     */
    val shouldConnect: Boolean
        get() = !suspended && !terminalBlocked &&
            (attached.isNotEmpty() || presence || ownerArmedReason || confirming || updating)

    /** Czy utrzymać istniejące łącze: jak [shouldConnect] albo komenda w toku (chyba że użytkownik rozłączył). */
    val shouldKeepLink: Boolean
        get() = shouldConnect || (commandPending && !suspended)

    /**
     * Powody łącza niezależne od ekranu telefonu (usługa `connectedDevice`, app_design §4): samochód, powiadomienie
     * „w zasięgu”, własna uzbrojona reguła albo aktualizacja firmware (transfer działa w usłudze po wyjściu z ekranu,
     * 15.5) — każdy ograniczony w czasie. Potwierdzanie rejestracji nie jest takim powodem: nie ma limitu prób, więc
     * porzucone w tle trzymałoby usługę bez końca; wraca z ekranem („Połącz”).
     * Komendę w toku po zamknięciu ekranu ocenia `:app` (zna widoczność UI).
     */
    val shouldConnectWithoutPhoneUi: Boolean
        get() = !suspended && !terminalBlocked && (UiSource.CAR in attached || presence || ownerArmedReason || updating)

    private val ownerArmedReason: Boolean
        get() = ownerArmed && !ownerArmedSuppressed && !ownerArmedExpired

    fun attach(source: UiSource) {
        attached += source
    }

    fun detach(source: UiSource) {
        attached -= source
    }

    fun updateOwnerArmed(snapshot: RuleSnapshot?) {
        ownerArmed = snapshot?.isOwnArmed() == true
    }

    /** Spec 11.3 krok 8: rejestracja kończy się dopiero po SESSION_READY — do tego czasu łącze jest potrzebne. */
    fun setConfirming(value: Boolean) {
        confirming = value
    }

    /** Komenda w toku utrzymuje łącze do rozstrzygnięcia (np. koniec projekcji Android Auto w trakcie OPEN). */
    fun setCommandPending(value: Boolean) {
        commandPending = value
    }

    /** Powiadomienie „brama w zasięgu” pokazane (SHOWN) — powód połączenia (spec 4.3). */
    fun setPresence(value: Boolean) {
        presence = value
    }

    /** Wyciszenie powiadomienia: własna uzbrojona reguła nie jest powodem połączenia (spec 4.3 pkt 4–5). */
    fun setOwnerArmedSuppressed(value: Boolean) {
        ownerArmedSuppressed = value
    }

    /**
     * Po utracie łącza nieaktualna własna reguła przestaje być powodem połączenia, gdy sterownik na pewno już ją
     * wykonał (spec 4.2, A73): bez tego telefon poza zasięgiem utrzymywałby usługę
     * i skanował bez końca. Zdejmuje to świeży snapshot z sesji READY.
     */
    fun setOwnerArmedExpired(value: Boolean) {
        ownerArmedExpired = value
    }

    /**
     * Transfer firmware albo oczekiwanie na sterownik po restarcie (najwyżej 90 s) — powód połączenia także bez
     * ekranu telefonu (spec 15.5).
     */
    fun setUpdating(value: Boolean) {
        updating = value
    }

    /** Pojawienie się sterownika znosi „Rozłącz” (spec 4.3 pkt 2), ale nie blokadę po odmowie terminalnej. */
    fun liftUserSuspension() {
        suspended = false
    }

    fun suspendByUser() {
        suspended = true
    }

    /** Świadoma akcja użytkownika: zdejmuje wstrzymanie, blokadę ponowień i zeruje backoff (spec 12.4). */
    fun resumeByUser() {
        suspended = false
        terminalBlocked = false
        nextBackoffMs = INITIAL_BACKOFF_MS
    }

    /** UNAVAILABLE/RATE_LIMITED: zwraca opóźnienie ponowienia (30 s, podwajane do 5 min). */
    fun onRetryableReject(): Long = nextBackoffMs.also { nextBackoffMs = minOf(it * 2, MAX_BACKOFF_MS) }

    /** Pozostałe odmowy: brak automatycznych ponowień do akcji użytkownika. */
    fun onTerminalReject() {
        terminalBlocked = true
    }

    fun onReady() {
        nextBackoffMs = INITIAL_BACKOFF_MS
    }

    /** Ponowne podpięcie UI: następny odstęp znów 30 s; bieżące odliczanie trwa (ochrona przed RATE_LIMITED). */
    fun resetBackoff() {
        nextBackoffMs = INITIAL_BACKOFF_MS
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 300_000L
    }
}
