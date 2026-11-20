package com.agendek.bramav2.ui

/** Etykiety przycisków i treść instrukcji — poza zamkniętą listą komunikatów (spec 3); nie opisują położenia. */
object Labels {
    const val OPEN = "OTWÓRZ"
    const val CLOSE = "ZAMKNIJ"
    const val CONNECT = "Połącz"
    const val DISCONNECT = "Rozłącz"
    const val REPAIR_BOND = "Napraw połączenie"
    const val OPEN_PAIRING_WINDOW = "Otwórz okno rejestracji"
    const val OPEN_PAIRING_WINDOW_HINT =
        "Dla kolejnego telefonu albo naprawy połączenia innego telefonu: okno działa 60 s, nowy telefon potrzebuje " +
            "QR albo PIN-u sterownika. Wymaga połączenia ze sterownikiem."
    const val ADD_GATE = "Dodaj bramę"
    const val SETTINGS = "Ustawienia"
    const val HISTORY = "Historia"
    const val BACK = "Wstecz"
    const val CONFIRM = "Potwierdź"
    const val CANCEL = "Anuluj"
    const val NEXT = "Dalej"
    const val DONE = "Gotowe"
    const val SCAN_QR = "Zeskanuj QR"
    const val ENTER_PIN = "Wpisz PIN"
    const val REGISTER = "Zarejestruj"
    const val SECTION_BEHAVIOR = "Działanie"
    const val SECTION_GATE = "Brama"
    const val SECTION_PAIRING = "Rejestracja telefonów"
    const val SECTION_CLIENTS = "Telefony z dostępem"
    const val CLIENTS_OPEN = "Pokaż telefony z dostępem"
    const val CLIENTS_HINT =
        "Telefony zapisane w sterowniku. Usunięty telefon traci dostęp do bramy i trzeba go zarejestrować od nowa."
    const val CLIENTS_LOADING = "Pobieranie listy…"
    const val CLIENTS_REFRESH = "Odśwież"
    const val CLIENTS_UNAVAILABLE = "Lista jest dostępna, gdy aplikacja jest połączona ze sterownikiem."
    const val BADGE_SELF = "Ten telefon"
    const val BADGE_CONNECTED = "Połączony"
    const val BADGE_OWNS_RULE = "Zarządza bramą"
    const val CLIENT_REMOVE = "Usuń"
    const val CLIENT_RENAME = "Zmień nazwę"
    const val CLIENT_NAME_FIELD = "Nazwa telefonu (do 20 bajtów)"
    const val CLIENT_OWNS_RULE_HINT = "Ten telefon zarządza otwartą bramą — usunięcie możliwe po jej zamknięciu."

    fun unnamedClient(n: Int) = "Telefon $n"

    fun removeClientDialog(name: String) =
        "Usunąć „$name”? Ten telefon straci dostęp do bramy. Aby wrócił, trzeba go zarejestrować od nowa (okno rejestracji)."
    const val PIN_SECTION = "Albo wpisz PIN"
    const val PIN_UNAVAILABLE =
        "Rejestracja PIN-em jest teraz niedostępna (limit prób, np. po restarcie bramy). Wciśnij przycisk na bramie albo zeskanuj kod QR."

    fun pinAttemptsLeft(n: Int) = "Pozostało prób PIN: $n z 5"
    const val PIN_FIELD = "PIN (6 cyfr z etykiety sterownika)"
    const val ADD_INTRO =
        "Włącz Bluetooth. Przytrzymaj przycisk sterownika ok. 3 s i puść, gdy dioda zacznie szybko migać — " +
            "okno rejestracji trwa 60 s. Potem zeskanuj kod QR z etykiety albo wpisz PIN."
    const val SCANNER_UNAVAILABLE = "Skaner kodów niedostępny na tym telefonie — wpisz PIN z etykiety."
    const val INVALID_QR = "To nie jest kod QR sterownika bramy — spróbuj ponownie albo wpisz PIN."
    const val REGISTERING = "Rejestracja w toku…"
    const val ASSOCIATE = "Powiąż bramę (powiadomienie, gdy jest w zasięgu)"
    const val ASSOCIATE_FAILED = "Nie udało się powiązać bramy — spróbuj ponownie w jej zasięgu."
    const val AUTO_CLOSE = "Automatyczne zamknięcie po utracie połączenia (dla następnego otwarcia)"
    const val PRESENCE = "Powiadomienie, gdy brama jest w zasięgu"
    const val PRESENCE_NEEDS_PERMISSION = "Wymaga zgody na powiadomienia w ustawieniach telefonu."
    const val PRESENCE_NEEDS_ASSOCIATION =
        "Brama nie jest powiązana z telefonem — powiadomienie się nie pojawi. Powiąż ją, gdy aplikacja jest połączona ze sterownikiem."
    const val GATE_NAME = "Nazwa bramy"
    const val SAVE = "Zapisz"
    const val RESET_TITLE = "Reset autoryzacji sterownika"
    const val RESET_STEPS =
        "Przytrzymaj przycisk sterownika co najmniej 10 s. Sterownik usunie wszystkie zarejestrowane telefony — " +
            "każdy trzeba będzie dodać od nowa."
    // Spec 9.4: po resecie (np. zgubiony telefon) sprawdzić bramę, bo autozamykanie zostało wyłączone.
    const val RESET_AFTER =
        "Po resecie sprawdź bramę i w razie potrzeby zamknij ją lokalnie albo z ponownie dodanego telefonu."
    // Spec 9.5: nieaktualny bond po stronie Androida. Pokazywane tylko bez konfiguracji — brak łącza, więc
    // „Zapomnij urządzenie” nie uruchamia autozamykania i nie wymaga ostrzeżenia z 4.1.
    const val FORGET_DEVICE_HINT =
        "Jeśli parowanie się nie udaje, usuń sterownik z listy sparowanych urządzeń w ustawieniach Bluetooth " +
            "(„Zapomnij urządzenie”) i spróbuj ponownie."
    const val REMOVE = "Usuń z telefonu"
    const val HISTORY_EMPTY = "Brak wpisów"
    const val PERMISSIONS = "Nadaj uprawnienia w ustawieniach aplikacji"

    // Aktualizacja firmware (spec 15.5) — etykiety; teksty stanu z listy zamkniętej.
    const val FIRMWARE = "Firmware"
    const val FIRMWARE_VERSION = "Wersja firmware"
    const val VERSION_UNKNOWN = "—"
    const val UPDATE_FIRMWARE = "Aktualizuj firmware"
    const val FIRMWARE_UPDATE = "Aktualizacja firmware"
    const val UPDATE = "Aktualizuj"
    const val RETRY = "Ponów"
    const val CANCEL_UPDATE = "Anuluj aktualizację"
    const val CLOSE_RESULT = "Zamknij"

    fun versionChange(from: String?, to: String) = "Wersja ${from ?: VERSION_UNKNOWN} → $to"
}
