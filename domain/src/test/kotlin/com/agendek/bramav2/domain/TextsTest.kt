package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.RuleReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextsTest {

    @Test
    fun everyMessageIdHasText() {
        for (id in MessageId.entries) {
            val args = when (id) {
                MessageId.RULE_WAITING, MessageId.RULE_BOOT_WAIT -> MessageArgs.Seconds(30)
                MessageId.RULE_FAULT -> MessageArgs.FaultCause(RuleReason.IO_FAULT)
                MessageId.OTA_PROGRESS -> MessageArgs.Percent(10)
                MessageId.OTA_DONE, MessageId.OTA_ROLLED_BACK -> MessageArgs.Version("1.0")
                else -> MessageArgs.None
            }
            assertTrue(id.name, Texts.text(UiMessage(id, args)).isNotBlank())
        }
    }

    @Test
    fun textsMatchSpecExactly() {
        assertEquals("Połączono ze sterownikiem", Texts.text(UiMessage(MessageId.CONNECTED)))
        assertEquals("Położenie bramy: nieznane", Texts.text(UiMessage(MessageId.POSITION_UNKNOWN)))
        assertEquals("Brak potwierdzenia. Wynik polecenia jest nieznany", Texts.text(UiMessage(MessageId.RESULT_UNKNOWN)))
        assertEquals(
            "Nie udało się zarejestrować — sprawdź PIN i czy okno rejestracji jest otwarte (po restarcie bramy PIN działa dopiero po wciśnięciu przycisku)",
            Texts.text(UiMessage(MessageId.PIN_REGISTRATION_FAILED)),
        )
        assertEquals(
            "Utracono połączenie z właścicielem — zamknięcie za ok. 30 s",
            Texts.text(UiMessage(MessageId.RULE_WAITING, MessageArgs.Seconds(30))),
        )
        assertEquals(
            "Autozamykanie zatrzymane: błąd sterownika (błąd wyjścia)",
            Texts.text(UiMessage(MessageId.RULE_FAULT, MessageArgs.FaultCause(RuleReason.IO_FAULT))),
        )
        assertEquals(
            "Autozamykanie zatrzymane: błąd sterownika (sterownik zajęty zbyt długo)",
            Texts.text(UiMessage(MessageId.RULE_FAULT, MessageArgs.FaultCause(RuleReason.BUSY_TIMEOUT))),
        )
    }

    @Test
    fun firmwareUpdateTextsMatchSpecExactly() { // spec 3, „Aktualizacja firmware (15.5)”
        val expected = mapOf(
            UiMessage(MessageId.WARN_OTA_RESTART) to
                "Sterownik uruchomi się ponownie i przez kilka sekund nie będzie można sterować bramą. Pozostań w zasięgu do końca przesyłania.",
            UiMessage(MessageId.OTA_PROGRESS, MessageArgs.Percent(42)) to "Przesyłanie firmware: 42%",
            UiMessage(MessageId.WARN_DISCONNECT_ABORTS_UPDATE) to "Rozłączenie przerwie aktualizację — firmware pozostanie bez zmian",
            UiMessage(MessageId.OTA_REBOOTING) to "Sterownik uruchamia się ponownie…",
            UiMessage(MessageId.OTA_DONE, MessageArgs.Version("0.2.0")) to "Zaktualizowano firmware do wersji 0.2.0",
            UiMessage(MessageId.OTA_ROLLED_BACK, MessageArgs.Version("0.1.0")) to
                "Aktualizacja nie powiodła się — sterownik wrócił do wersji 0.1.0",
            UiMessage(MessageId.OTA_NO_RECONNECT) to "Brak połączenia ze sterownikiem po aktualizacji — sprawdź sygnalizację LED",
            UiMessage(MessageId.OTA_FILE_INVALID) to "Wybrany plik nie jest firmware tego sterownika",
            UiMessage(MessageId.OTA_IMAGE_REJECTED) to "Sterownik odrzucił firmware: nieprawidłowy podpis lub uszkodzony plik",
            UiMessage(MessageId.OTA_DOWNGRADE) to "Ta wersja firmware jest starsza niż zainstalowana — aktualizacja odrzucona",
            UiMessage(MessageId.OTA_RULE_ACTIVE) to
                "Aktualizacja niemożliwa przy aktywnym autozamykaniu — zamknij bramę albo wyślij otwarcie bez autozamykania",
            UiMessage(MessageId.OTA_BUSY) to "Trwa aktualizacja z innego telefonu",
            UiMessage(MessageId.OTA_LINK_TOO_SMALL) to "Połączenie nie obsługuje aktualizacji (za małe pakiety BLE)",
            UiMessage(MessageId.OTA_STORAGE_FAILURE) to "Błąd pamięci sterownika — aktualizacja przerwana, firmware bez zmian",
            UiMessage(MessageId.OTA_ABORTED) to "Aktualizacja przerwana — firmware bez zmian",
        )
        for ((message, text) in expected) assertEquals(message.id.name, text, Texts.text(message))
        assertEquals(15, expected.size)
    }

    @Test
    fun bondRepairTextsMatchSpecExactly() { // spec 3, naprawa bondu (9.5)
        val expected = mapOf(
            MessageId.BOND_MISSING to
                "Brak parowania Bluetooth ze sterownikiem — otwórz okno rejestracji (przycisk sterownika 3 s albo inny " +
                "zarejestrowany telefon) i wybierz „Napraw połączenie”",
            MessageId.REJECT_WINDOW_CLOSED to
                "Okno rejestracji jest zamknięte — przytrzymaj przycisk sterownika 3 s albo otwórz je z zarejestrowanego telefonu",
            MessageId.PAIRING_WINDOW_OPENED to
                "Okno rejestracji otwarte na 60 s — dodaj telefon albo napraw połączenie na drugim telefonie",
            MessageId.REPAIR_BOND_FAILED to
                "Parowanie Bluetooth nie powiodło się — sprawdź, czy okno rejestracji jest otwarte; jeśli to się powtarza, " +
                "usuń sterownik w ustawieniach Bluetooth („Zapomnij urządzenie”)",
            MessageId.REPAIR_STORAGE_FAILURE to "Błąd pamięci sterownika — naprawa połączenia nie została zakończona",
            MessageId.REPAIR_DONE to "Połączenie ze sterownikiem naprawione",
        )
        for ((id, text) in expected) assertEquals(id.name, text, Texts.text(UiMessage(id)))
    }

    @Test
    fun noTextDescribesPosition() {
        // Wyjątek: wymagane przez spec ostrzeżenie warunkowe „Brama otwarta przed resetem pozostanie otwarta.”
        val statement = Regex("Brama (jest )?(otwarta|zamknięta)(?! przed resetem)")
        for (id in MessageId.entries) {
            assertFalse(id.name, statement.containsMatchIn(Texts.text(UiMessage(id, MessageArgs.Seconds(1)))))
        }
    }

    @Test
    fun carPositionIsShort() {
        assertEquals("Położenie nieznane", Texts.car(UiMessage(MessageId.POSITION_UNKNOWN)))
    }

    @Test
    fun carOtherTextsEqualPhone() {
        for (id in MessageId.entries - MessageId.POSITION_UNKNOWN) {
            val m = UiMessage(id, MessageArgs.Seconds(5))
            assertEquals(id.name, Texts.text(m), Texts.car(m))
        }
    }
}
