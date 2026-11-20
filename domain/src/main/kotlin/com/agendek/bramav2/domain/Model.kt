package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.QrError
import com.agendek.bramav2.protocol.RuleSnapshot

/** Położenie bramy jest zawsze nieznane (spec decyzja 1). Nie dodawać innych wartości. */
enum class GatePosition { UNKNOWN }

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, SECURING_LINK,
    AUTHENTICATING, READY, REKEYING, ERROR,
}

enum class CredentialState { NONE, UNCONFIRMED, ACTIVE }

enum class CommandOutcome { IDLE, IN_FLIGHT, OUTPUT_TRIGGERED, REJECTED, OUTCOME_UNKNOWN }

sealed interface GateCommand {
    data class Open(val autoCloseOnDisconnect: Boolean) : GateCommand
    data object Close : GateCommand
}

data class LastCommand(val command: GateCommand, val outcome: CommandOutcome, val ackResult: AckResult?)

/**
 * Stan reguły dla UI (spec 3). `fresh` tylko po snapshocie z bieżącej sesji READY;
 * `refreshing` odróżnia „Odświeżanie stanu reguły…” od „Stan reguły nieaktualny — brak połączenia”.
 */
data class RuleView(val snapshot: RuleSnapshot?, val fresh: Boolean, val refreshing: Boolean)

enum class UiSource { PHONE, CAR }

/**
 * Powiadomienie „brama w zasięgu” (spec 4.3): ukryte; pokazane (sterownik w zasięgu, łącze utrzymywane);
 * wyciszone po odrzuceniu powiadomienia — do zniknięcia sterownika z zasięgu.
 */
enum class PresenceNotification { HIDDEN, SHOWN, QUIET }

enum class Confirmation { DISCONNECT_ARMS_AUTOCLOSE, DISCONNECT_ABORTS_UPDATE, REMOVE_FROM_PHONE, ENABLE_AUTO_CLOSE }

/** Aktualizacja firmware przez BLE (spec 15.5, ota_design §5.1). */
sealed interface FirmwareUpdate {
    /** Plik sprawdzony; potwierdzenie „wersja X → Y” z ostrzeżeniem `WARN_OTA_RESTART`. */
    data class Confirm(val currentVersion: String?, val newVersion: String) : FirmwareUpdate

    data class Transferring(val percent: Int) : FirmwareUpdate

    /** END odrzucony (`OTA_RULE_ACTIVE`/`OTA_BUSY`): dane zostają u sterownika, END można ponowić. */
    data class EndRejected(val message: UiMessage) : FirmwareUpdate

    /** Po `OTA_OK` na END (albo utracie sesji po wysłaniu END): oczekiwanie na READY i wersję, najdłużej 90 s. */
    data object Rebooting : FirmwareUpdate

    /** Koniec: sukces, powrót do poprzedniej wersji albo błąd — tekst z rozdziału 3. */
    data class Finished(val message: UiMessage) : FirmwareUpdate
}

/** Błędy systemowe telefonu zgłaszane przez `:app` (spec 3, A27). */
enum class SystemProblem {
    BLUETOOTH_OFF, BLUETOOTH_PERMISSION_MISSING, CAMERA_PERMISSION_MISSING,
    BACKGROUND_START_BLOCKED, CREDENTIAL_LOCKED,
}

sealed interface RegistrationState {
    data object Pairing : RegistrationState
    data object SavingCredential : RegistrationState
    data object Committing : RegistrationState
    data object ConfirmingSession : RegistrationState
    data object Done : RegistrationState
    data class InvalidQr(val error: QrError) : RegistrationState
    data class Failed(val message: UiMessage) : RegistrationState
}

/** Lista telefonów na sterowniku (client_management_design.md §5.3): [Idle] = brak aktualnej listy (UI odświeża ją, gdy to możliwe). */
sealed interface ClientsUi {
    data object Idle : ClientsUi
    data object Loading : ClientsUi
    data class Ready(val clients: List<com.agendek.bramav2.protocol.ClientEntry>) : ClientsUi
    data class Error(val message: UiMessage) : ClientsUi
}

/** Kompletny stan dla UI telefonu i samochodu. Teksty wyłącznie jako [UiMessage]. */
data class GateUiState(
    val gateName: String?,
    val credential: CredentialState,
    val connection: ConnectionState,
    val position: GatePosition = GatePosition.UNKNOWN,
    val rule: RuleView,
    val lastCommand: LastCommand?,
    val nextOpenAutoClose: Boolean,
    val controlsEnabled: Boolean,
    val confirmations: Map<UiSource, Confirmation>,
    /** Ostrzeżenia do bieżącego potwierdzenia telefonu. */
    val warnings: List<UiMessage>,
    val registration: RegistrationState?,
    val systemProblem: SystemProblem?,
    /** Po potwierdzonym „Rozłącz”: brak automatycznego łączenia do „Połącz”. */
    val connectSuspended: Boolean,
    /** Ostatnia odmowa/błąd handshake (spec 12.4); czyszczona po READY. */
    val handshakeProblem: UiMessage?,
    val messages: List<UiMessage>,
    val carMessages: List<UiMessage>,
    val notification: UiMessage?,
    /** Powiadomienie „brama w zasięgu” (spec 4.3). */
    val presence: PresenceNotification = PresenceNotification.HIDDEN,
    /** Przyciski OTWÓRZ/ZAMKNIJ w powiadomieniu: SHOWN, READY i brak komendy w toku. */
    val notificationActionsAvailable: Boolean = false,
    /** Ustawienie powiadomienia „brama w zasięgu” (spec 4.3 pkt 7). */
    val presenceNotificationEnabled: Boolean = true,
    /**
     * Łącze potrzebne poza ekranem telefonu (app_design §4): trwa także w czasie ponownego łączenia po utracie
     * BLE, więc chwilowe DISCONNECTED nie zatrzymuje usługi `connectedDevice`.
     */
    val backgroundLinkNeeded: Boolean = false,
    /** Wersja firmware z Device Information bieżącego łącza (spec 8.2); `null` bez łącza lub przy błędzie odczytu. */
    val firmwareVersion: String? = null,
    val firmwareUpdate: FirmwareUpdate? = null,
    /** Pozycja „Aktualizacja firmware” aktywna: READY, ACTIVE, bit 6 `capabilities` i brak aktualizacji w toku (15.5). */
    val firmwareUpdateAvailable: Boolean = false,
    /** Akcja „Napraw połączenie” (spec 9.5): po braku bondu lub nieudanej naprawie; czyszczona po READY. */
    val bondRepairAvailable: Boolean = false,
    /** Akcja „Otwórz okno rejestracji” (phone_window_design): READY, ACTIVE, bit 7 i brak innego żądania w toku. */
    val pairingWindowAvailable: Boolean = false,
    /** Sterownik bieżącej sesji obsługuje okno z telefonu (bit 7 `capabilities`): pozycja widoczna w Ustawieniach. */
    val pairingWindowSupported: Boolean = false,
    /** Lista telefonów na sterowniku (ekran „Telefony z dostępem”). */
    val clients: ClientsUi = ClientsUi.Idle,
    /** Odświeżenie listy, usunięcie i zmiana nazwy dostępne: READY, ACTIVE i brak innego żądania w toku. */
    val clientsAvailable: Boolean = false,
) {
    companion object {
        val INITIAL = GateUiState(
            gateName = null,
            credential = CredentialState.NONE,
            connection = ConnectionState.DISCONNECTED,
            rule = RuleView(snapshot = null, fresh = false, refreshing = false),
            lastCommand = null,
            nextOpenAutoClose = false,
            controlsEnabled = false,
            confirmations = emptyMap(),
            warnings = emptyList(),
            registration = null,
            systemProblem = null,
            connectSuspended = false,
            handshakeProblem = null,
            messages = emptyList(),
            carMessages = emptyList(),
            notification = null,
        )
    }
}
