package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.RuleReason

/**
 * Zamknięta lista komunikatów spec 3 — jedna pozycja na tekst. Nowe pozycje wyłącznie przez zmianę specyfikacji.
 * Pozycje `LABEL_*` to etykiety, które spec 3 wyłącza z listy zamkniętej.
 */
enum class MessageId {
    // Położenie i połączenie
    POSITION_UNKNOWN,
    CONNECTED,
    CONNECTING,
    NOT_CONNECTED,
    CONFIGURE_ON_PHONE,

    // Błędy systemowe telefonu
    BLUETOOTH_OFF,
    BLUETOOTH_PERMISSION_MISSING,
    CAMERA_PERMISSION_MISSING,
    BACKGROUND_START_BLOCKED,
    CREDENTIAL_LOCKED,

    // Wyniki komend
    RESULT_OPEN_TRIGGERED,
    RESULT_CLOSE_TRIGGERED,
    RESULT_UNKNOWN,
    RESULT_BUSY,
    RESULT_LEASE_OWNED_BY_OTHER,
    RESULT_INTERLOCK_BLOCKED,
    RESULT_IO_FAULT,
    RESULT_COMPATIBILITY_ERROR,

    // Reguła autozamykania
    RULE_MONITORING_SELF,
    RULE_MONITORING_OTHER,
    RULE_WAITING,
    RULE_BOOT_WAIT,
    RULE_BLOCKED,
    RULE_FAULT,
    RULE_STALE,
    RULE_REFRESHING,
    RULE_NONE,
    RULE_REBOOT_DISARMED,

    // Ostrzeżenia i potwierdzenia
    WARN_ENABLE_AUTO_CLOSE,
    WARN_DISCONNECT_ARMS,
    WARN_REMOVE_MAY_CLOSE,
    WARN_REMOVE_NOT_REVOKED,
    WARN_RESET_NO_SIGNAL,

    // Rejestracja
    REGISTRATION_DONE,
    REGISTRATION_INCOMPLETE,
    PIN_REGISTRATION_FAILED,

    // Odmowy handshake (12.4)
    REJECT_AUTH_FAILED,
    REJECT_UNAVAILABLE,
    REJECT_RATE_LIMITED,
    REJECT_NO_CAPACITY,
    REJECT_CLIENT_ALREADY_ACTIVE,
    REJECT_REPLACEMENT_BLOCKED,
    REJECT_WINDOW_CLOSED,
    REJECT_STORAGE_FAILURE,

    // Naprawa bondu przy zachowanym K_client (9.5)
    BOND_MISSING,
    REPAIR_BOND_FAILED,
    REPAIR_STORAGE_FAILURE,
    REPAIR_DONE,

    // Okno rejestracji otwierane z telefonu (phone_window_design)
    PAIRING_WINDOW_OPENED,

    // Telefony z dostępem (client_management_design.md)
    CLIENT_REMOVED,
    CLIENT_ALREADY_REMOVED,
    CLIENT_CANNOT_REMOVE_SELF,
    CLIENT_OWNS_RULE,
    CLIENTS_STORAGE_FAILURE,
    CLIENT_NAME_SET,
    CLIENTS_RESULT_UNKNOWN,

    // Aktualizacja firmware (15.5)
    WARN_OTA_RESTART,
    WARN_DISCONNECT_ABORTS_UPDATE,
    OTA_PROGRESS,
    OTA_REBOOTING,
    OTA_DONE,
    OTA_ROLLED_BACK,
    OTA_NO_RECONNECT,
    OTA_FILE_INVALID,
    OTA_IMAGE_REJECTED,
    OTA_DOWNGRADE,
    OTA_RULE_ACTIVE,
    OTA_BUSY,
    OTA_LINK_TOO_SMALL,
    OTA_STORAGE_FAILURE,
    OTA_ABORTED,

    // Etykiety poza listą zamkniętą
    LABEL_NEXT_OPEN_AUTO_CLOSE_ON,
    LABEL_NEXT_OPEN_AUTO_CLOSE_OFF,
}

sealed interface MessageArgs {
    data object None : MessageArgs

    /** Orientacyjna liczba sekund dla WAITING/BOOT_WAIT. */
    data class Seconds(val value: Long) : MessageArgs

    /** Przyczyna FAULT: IO_FAULT, STORAGE_FAILURE albo BUSY_TIMEOUT. */
    data class FaultCause(val reason: RuleReason) : MessageArgs

    /** Postęp przesyłania firmware (0–100). */
    data class Percent(val value: Int) : MessageArgs

    /** Wersja firmware (`esp_app_desc_t.version`). */
    data class Version(val value: String) : MessageArgs
}

data class UiMessage(val id: MessageId, val args: MessageArgs = MessageArgs.None)
