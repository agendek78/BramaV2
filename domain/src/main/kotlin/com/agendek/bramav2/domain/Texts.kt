package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.RuleReason

/** Nazwa bramy, gdy użytkownik nie nadał własnej (telefon i samochód). */
const val DEFAULT_GATE_NAME = "Brama"

/** Teksty zamkniętej listy (spec 3, wersja 1.5) — jedyne źródło komunikatów o stanie w UI i powiadomieniach. */
object Texts {

    /** Wariant samochodowy (spec 3, 5.2): skrót wyłącznie dla położenia, pozostałe teksty jak na telefonie. */
    fun car(message: UiMessage): String =
        if (message.id == MessageId.POSITION_UNKNOWN) "Położenie nieznane" else text(message)

    fun text(message: UiMessage): String = when (message.id) {
        MessageId.POSITION_UNKNOWN -> "Położenie bramy: nieznane"
        MessageId.CONNECTED -> "Połączono ze sterownikiem"
        MessageId.CONNECTING -> "Łączenie ze sterownikiem…"
        MessageId.NOT_CONNECTED -> "Brak połączenia ze sterownikiem"
        MessageId.CONFIGURE_ON_PHONE -> "Skonfiguruj aplikację na telefonie po zatrzymaniu"
        MessageId.BLUETOOTH_OFF -> "Bluetooth jest wyłączony — włącz Bluetooth, aby sterować bramą"
        MessageId.BLUETOOTH_PERMISSION_MISSING -> "Brak uprawnień Bluetooth — nadaj je w ustawieniach aplikacji"
        MessageId.CAMERA_PERMISSION_MISSING ->
            "Brak uprawnienia do aparatu — zeskanuj QR systemowym skanerem lub wprowadź dane ręcznie"
        MessageId.BACKGROUND_START_BLOCKED -> "Nie można uruchomić połączenia w tle — otwórz aplikację na telefonie"
        MessageId.CREDENTIAL_LOCKED -> "Poświadczenie niedostępne do pierwszego odblokowania telefonu"
        MessageId.RESULT_OPEN_TRIGGERED -> "Sterownik potwierdził sygnał otwarcia"
        MessageId.RESULT_CLOSE_TRIGGERED -> "Sterownik potwierdził sygnał zamknięcia"
        MessageId.RESULT_UNKNOWN -> "Brak potwierdzenia. Wynik polecenia jest nieznany"
        MessageId.RESULT_BUSY -> "Sterownik zajęty — polecenie nie zostało wykonane"
        MessageId.RESULT_LEASE_OWNED_BY_OTHER -> "Otwarcie zablokowane: regułę autozamykania ustawił inny telefon"
        MessageId.RESULT_INTERLOCK_BLOCKED -> "Polecenie zablokowane przez blokadę instalacji"
        MessageId.RESULT_IO_FAULT -> "Błąd wyjścia sterownika — brak potwierdzenia sygnału"
        MessageId.RESULT_COMPATIBILITY_ERROR -> "Błąd zgodności aplikacji i sterownika"
        MessageId.RULE_MONITORING_SELF -> "Reguła autozamykania: monitorowanie aktywne"
        MessageId.RULE_MONITORING_OTHER -> "Autozamykanie innego telefonu jest aktywne"
        MessageId.RULE_WAITING -> "Utracono połączenie z właścicielem — zamknięcie za ok. ${seconds(message)} s"
        MessageId.RULE_BOOT_WAIT -> "Sterownik uruchomiony ponownie — oczekiwanie na właściciela, ok. ${seconds(message)} s"
        MessageId.RULE_BLOCKED -> "Autozamykanie wstrzymane przez blokadę instalacji"
        MessageId.RULE_FAULT -> "Autozamykanie zatrzymane: błąd sterownika (${faultCause(message)})"
        MessageId.RULE_STALE -> "Stan reguły nieaktualny — brak połączenia"
        MessageId.RULE_REFRESHING -> "Odświeżanie stanu reguły…"
        MessageId.RULE_NONE -> "Brak aktywnej reguły autozamykania"
        MessageId.RULE_REBOOT_DISARMED ->
            "Sterownik uruchomił się po zaniku zasilania. Autozamykanie zostało wyłączone; napęd powinien był sam zamknąć bramę."
        MessageId.WARN_ENABLE_AUTO_CLOSE ->
            "Po utracie połączenia z tym telefonem sterownik poda sygnał zamknięcia po ok. 30 s. " +
                "Może to nastąpić także po wyłączeniu Bluetooth lub zatrzymaniu aplikacji."
        MessageId.WARN_DISCONNECT_ARMS -> "Rozłączenie uruchomi autozamykanie: sterownik poda sygnał zamknięcia po ok. 30 s"
        MessageId.WARN_REMOVE_MAY_CLOSE -> "Usunięcie zerwie połączenie i sterownik może podać sygnał zamknięcia za ok. 30 s"
        MessageId.WARN_REMOVE_NOT_REVOKED ->
            "Usunięcie z telefonu nie odwołuje dostępu w sterowniku. Aby odwołać zgubiony telefon, wykonaj reset autoryzacji."
        MessageId.WARN_RESET_NO_SIGNAL ->
            "Reset nie podaje sygnału i wyłącza autozamykanie. Brama otwarta przed resetem pozostanie otwarta."
        MessageId.REGISTRATION_DONE -> "Telefon zarejestrowany. Połączono ze sterownikiem"
        MessageId.REGISTRATION_INCOMPLETE -> "Rejestracja niezakończona — połącz się ponownie ze sterownikiem"
        MessageId.PIN_REGISTRATION_FAILED -> "Nie udało się zarejestrować — sprawdź PIN i czy okno rejestracji jest otwarte (po restarcie bramy PIN działa dopiero po wciśnięciu przycisku)"
        MessageId.REJECT_AUTH_FAILED -> "Nie udało się uwierzytelnić sterownika"
        MessageId.REJECT_UNAVAILABLE -> "Sterownik niedostępny — sprawdź sygnalizację LED"
        MessageId.REJECT_RATE_LIMITED -> "Zbyt wiele prób — spróbuj ponownie za chwilę"
        MessageId.REJECT_NO_CAPACITY -> "Brak miejsca na kolejny telefon — wymagany reset autoryzacji sterownika"
        MessageId.REJECT_CLIENT_ALREADY_ACTIVE -> "Ten telefon jest już zarejestrowany"
        MessageId.REJECT_REPLACEMENT_BLOCKED ->
            "Zastąpienie zablokowane: poprzedni telefon ma aktywne autozamykanie lub połączenie"
        MessageId.REJECT_WINDOW_CLOSED ->
            "Okno rejestracji jest zamknięte — przytrzymaj przycisk sterownika 3 s albo otwórz je z zarejestrowanego telefonu"
        MessageId.REJECT_STORAGE_FAILURE -> "Błąd pamięci sterownika — rejestracja nie została zakończona"
        MessageId.BOND_MISSING ->
            "Brak parowania Bluetooth ze sterownikiem — otwórz okno rejestracji (przycisk sterownika 3 s albo inny " +
                "zarejestrowany telefon) i wybierz „Napraw połączenie”"
        MessageId.REPAIR_BOND_FAILED ->
            "Parowanie Bluetooth nie powiodło się — sprawdź, czy okno rejestracji jest otwarte; jeśli to się powtarza, " +
                "usuń sterownik w ustawieniach Bluetooth („Zapomnij urządzenie”)"
        MessageId.REPAIR_STORAGE_FAILURE -> "Błąd pamięci sterownika — naprawa połączenia nie została zakończona"
        MessageId.REPAIR_DONE -> "Połączenie ze sterownikiem naprawione"
        MessageId.CLIENT_REMOVED -> "Usunięto telefon"
        MessageId.CLIENT_ALREADY_REMOVED -> "Ten telefon został już usunięty"
        MessageId.CLIENT_CANNOT_REMOVE_SELF -> "Nie można usunąć tego telefonu"
        MessageId.CLIENT_OWNS_RULE -> "Ten telefon zarządza otwartą bramą — zamknij bramę albo poczekaj na autozamknięcie i spróbuj ponownie"
        MessageId.CLIENTS_STORAGE_FAILURE -> "Nie udało się zapisać zmiany w sterowniku"
        MessageId.CLIENT_NAME_SET -> "Nazwa telefonu zapisana"
        MessageId.CLIENTS_RESULT_UNKNOWN -> "Nie wiadomo, czy zmiana została wykonana — odśwież listę"
        MessageId.PAIRING_WINDOW_OPENED -> "Okno rejestracji otwarte na 60 s — dodaj telefon albo napraw połączenie na drugim telefonie"
        MessageId.WARN_OTA_RESTART ->
            "Sterownik uruchomi się ponownie i przez kilka sekund nie będzie można sterować bramą. " +
                "Pozostań w zasięgu do końca przesyłania."
        MessageId.WARN_DISCONNECT_ABORTS_UPDATE -> "Rozłączenie przerwie aktualizację — firmware pozostanie bez zmian"
        MessageId.OTA_PROGRESS -> "Przesyłanie firmware: ${(message.args as? MessageArgs.Percent)?.value ?: 0}%"
        MessageId.OTA_REBOOTING -> "Sterownik uruchamia się ponownie…"
        MessageId.OTA_DONE -> "Zaktualizowano firmware do wersji ${version(message)}"
        MessageId.OTA_ROLLED_BACK -> "Aktualizacja nie powiodła się — sterownik wrócił do wersji ${version(message)}"
        MessageId.OTA_NO_RECONNECT -> "Brak połączenia ze sterownikiem po aktualizacji — sprawdź sygnalizację LED"
        MessageId.OTA_FILE_INVALID -> "Wybrany plik nie jest firmware tego sterownika"
        MessageId.OTA_IMAGE_REJECTED -> "Sterownik odrzucił firmware: nieprawidłowy podpis lub uszkodzony plik"
        MessageId.OTA_DOWNGRADE -> "Ta wersja firmware jest starsza niż zainstalowana — aktualizacja odrzucona"
        MessageId.OTA_RULE_ACTIVE ->
            "Aktualizacja niemożliwa przy aktywnym autozamykaniu — zamknij bramę albo wyślij otwarcie bez autozamykania"
        MessageId.OTA_BUSY -> "Trwa aktualizacja z innego telefonu"
        MessageId.OTA_LINK_TOO_SMALL -> "Połączenie nie obsługuje aktualizacji (za małe pakiety BLE)"
        MessageId.OTA_STORAGE_FAILURE -> "Błąd pamięci sterownika — aktualizacja przerwana, firmware bez zmian"
        MessageId.OTA_ABORTED -> "Aktualizacja przerwana — firmware bez zmian"
        // Etykiety poza listą zamkniętą (spec 3): ustawienie następnego otwarcia, nie stan reguły.
        MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_ON -> "Następne otwarcie: z automatycznym zamknięciem"
        MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_OFF -> "Następne otwarcie: bez automatycznego zamknięcia"
    }

    private fun version(message: UiMessage): String = (message.args as? MessageArgs.Version)?.value ?: "?"

    private fun seconds(message: UiMessage): Long = (message.args as? MessageArgs.Seconds)?.value ?: 0L

    private fun faultCause(message: UiMessage): String = when ((message.args as? MessageArgs.FaultCause)?.reason) {
        RuleReason.STORAGE_FAILURE -> "błąd pamięci przy resecie autoryzacji"
        RuleReason.BUSY_TIMEOUT -> "sterownik zajęty zbyt długo"
        else -> "błąd wyjścia"
    }
}
