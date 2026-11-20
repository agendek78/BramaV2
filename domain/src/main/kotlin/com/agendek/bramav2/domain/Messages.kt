package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.OtaResult
import com.agendek.bramav2.protocol.OwnerRelation

/** Mapowanie stanu na komunikaty z zamkniętej listy spec 3. */
object Messages {

    fun connection(state: ConnectionState): UiMessage = UiMessage(
        when (state) {
            ConnectionState.READY -> MessageId.CONNECTED
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> MessageId.NOT_CONNECTED
            ConnectionState.SCANNING, ConnectionState.CONNECTING, ConnectionState.SECURING_LINK,
            ConnectionState.AUTHENTICATING, ConnectionState.REKEYING -> MessageId.CONNECTING
        },
    )

    /** Przy `fresh = false` wyłącznie informacja o nieaktualności, bez ostatniej fazy (domain_design §6.3). */
    fun rule(view: RuleView): UiMessage {
        val s = view.snapshot
        if (!view.fresh || s == null) {
            return UiMessage(if (view.refreshing) MessageId.RULE_REFRESHING else MessageId.RULE_STALE)
        }
        return when (s.phase) {
            AutoClosePhase.DISARMED ->
                UiMessage(if (s.isRebootDisarmed()) MessageId.RULE_REBOOT_DISARMED else MessageId.RULE_NONE)
            AutoClosePhase.MONITORING ->
                UiMessage(if (s.ownerRelation == OwnerRelation.SELF) MessageId.RULE_MONITORING_SELF else MessageId.RULE_MONITORING_OTHER)
            AutoClosePhase.WAITING -> UiMessage(MessageId.RULE_WAITING, MessageArgs.Seconds(secondsFrom(s.remainingMs)))
            AutoClosePhase.BOOT_WAIT -> UiMessage(MessageId.RULE_BOOT_WAIT, MessageArgs.Seconds(secondsFrom(s.remainingMs)))
            AutoClosePhase.BLOCKED -> UiMessage(MessageId.RULE_BLOCKED)
            AutoClosePhase.FAULT -> UiMessage(MessageId.RULE_FAULT, MessageArgs.FaultCause(s.reason))
        }
    }

    fun commandResult(last: LastCommand): UiMessage? = when (last.outcome) {
        CommandOutcome.IDLE, CommandOutcome.IN_FLIGHT -> null
        CommandOutcome.OUTCOME_UNKNOWN -> UiMessage(MessageId.RESULT_UNKNOWN)
        CommandOutcome.OUTPUT_TRIGGERED, CommandOutcome.REJECTED -> UiMessage(ackMessage(last.command, last.ackResult))
    }

    private fun ackMessage(command: GateCommand, ack: AckResult?): MessageId = when (ack) {
        AckResult.OUTPUT_TRIGGERED ->
            if (command is GateCommand.Open) MessageId.RESULT_OPEN_TRIGGERED else MessageId.RESULT_CLOSE_TRIGGERED
        AckResult.BUSY -> MessageId.RESULT_BUSY
        AckResult.LEASE_OWNED_BY_OTHER -> MessageId.RESULT_LEASE_OWNED_BY_OTHER
        AckResult.INTERLOCK_BLOCKED -> MessageId.RESULT_INTERLOCK_BLOCKED
        AckResult.IO_FAULT -> MessageId.RESULT_IO_FAULT
        // STORAGE_FAILURE jest zarezerwowany i firmware go nie wysyła (spec 13.2)
        // WINDOW_OPENED dotyczy wyłącznie OPEN_PAIRING_WINDOW — dla komendy bramy to błąd zgodności
        // Wyniki zarządzania telefonami dotyczą wyłącznie REMOVE_CLIENT/SET_CLIENT_NAME — dla komendy bramy to błąd zgodności
        AckResult.STORAGE_FAILURE, AckResult.INVALID_ARGUMENT, AckResult.UNSUPPORTED_TYPE, AckResult.WINDOW_OPENED,
        AckResult.CLIENT_REMOVED, AckResult.NAME_SET, AckResult.CLIENT_NOT_FOUND, AckResult.CANNOT_REMOVE_SELF,
        AckResult.CLIENT_OWNS_RULE, null ->
            MessageId.RESULT_COMPATIBILITY_ERROR
    }

    fun handshakeReject(reason: HandshakeRejectReason): UiMessage = UiMessage(
        when (reason) {
            HandshakeRejectReason.AUTH_FAILED -> MessageId.REJECT_AUTH_FAILED
            HandshakeRejectReason.UNAVAILABLE -> MessageId.REJECT_UNAVAILABLE
            HandshakeRejectReason.RATE_LIMITED -> MessageId.REJECT_RATE_LIMITED
            HandshakeRejectReason.NO_CAPACITY -> MessageId.REJECT_NO_CAPACITY
            HandshakeRejectReason.CLIENT_ALREADY_ACTIVE -> MessageId.REJECT_CLIENT_ALREADY_ACTIVE
            HandshakeRejectReason.REPLACEMENT_BLOCKED -> MessageId.REJECT_REPLACEMENT_BLOCKED
            HandshakeRejectReason.STORAGE_FAILURE -> MessageId.REJECT_STORAGE_FAILURE
            HandshakeRejectReason.WINDOW_CLOSED -> MessageId.REJECT_WINDOW_CLOSED
        },
    )

    fun systemProblem(problem: SystemProblem): UiMessage = UiMessage(
        when (problem) {
            SystemProblem.BLUETOOTH_OFF -> MessageId.BLUETOOTH_OFF
            SystemProblem.BLUETOOTH_PERMISSION_MISSING -> MessageId.BLUETOOTH_PERMISSION_MISSING
            SystemProblem.CAMERA_PERMISSION_MISSING -> MessageId.CAMERA_PERMISSION_MISSING
            SystemProblem.BACKGROUND_START_BLOCKED -> MessageId.BACKGROUND_START_BLOCKED
            SystemProblem.CREDENTIAL_LOCKED -> MessageId.CREDENTIAL_LOCKED
        },
    )

    /** Tekst odmowy `OTA_ACK` (spec 3, 15.5); nieznany kod dekoduje się już jako IMAGE_REJECTED. */
    fun otaResult(result: OtaResult): UiMessage = UiMessage(
        when (result) {
            OtaResult.BUSY -> MessageId.OTA_BUSY
            OtaResult.RULE_ACTIVE -> MessageId.OTA_RULE_ACTIVE
            OtaResult.LINK_TOO_SMALL -> MessageId.OTA_LINK_TOO_SMALL
            OtaResult.TOO_LARGE -> MessageId.OTA_FILE_INVALID
            OtaResult.IMAGE_REJECTED -> MessageId.OTA_IMAGE_REJECTED
            OtaResult.DOWNGRADE -> MessageId.OTA_DOWNGRADE
            OtaResult.STORAGE_FAILURE -> MessageId.OTA_STORAGE_FAILURE
            OtaResult.OK, OtaResult.BAD_OFFSET, OtaResult.NOT_IN_PROGRESS, OtaResult.INVALID_ARGUMENT -> MessageId.OTA_ABORTED
        },
    )

    /** Wiersz stanu aktualizacji (postęp, restart, wynik); potwierdzenie pokazuje ekran aktualizacji. */
    fun firmwareUpdate(update: FirmwareUpdate?): UiMessage? = when (update) {
        null, is FirmwareUpdate.Confirm -> null
        is FirmwareUpdate.Transferring -> UiMessage(MessageId.OTA_PROGRESS, MessageArgs.Percent(update.percent))
        is FirmwareUpdate.EndRejected -> update.message
        FirmwareUpdate.Rebooting -> UiMessage(MessageId.OTA_REBOOTING)
        is FirmwareUpdate.Finished -> update.message
    }

    fun nextOpenLabel(autoClose: Boolean): UiMessage =
        UiMessage(if (autoClose) MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_ON else MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_OFF)

    /** Orientacyjne sekundy, zaokrąglone w górę (spec 12.2: czas tylko orientacyjny). */
    fun secondsFrom(remainingMs: Long): Long = maxOf(0L, (remainingMs + 999) / 1000)
}
