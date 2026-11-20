package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.FirmwareUpdate
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.Messages
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.UiMessage

enum class FirmwareAction { UPDATE, CANCEL, RETRY_END, CANCEL_UPDATE, CLOSE }

/**
 * Model ekranu aktualizacji firmware (spec 15.5, ota_design §6): etykieta „wersja X → Y”, tekst z listy zamkniętej,
 * postęp (0..1, `null` bez paska) i dostępne akcje.
 */
data class FirmwareUi(
    val versionChange: String?,
    val texts: List<String>,
    val progress: Float?,
    val actions: List<FirmwareAction>,
)

fun firmwareUi(s: GateUiState): FirmwareUi = when (val u = s.firmwareUpdate) {
    null -> FirmwareUi(null, emptyList(), null, emptyList())
    is FirmwareUpdate.Confirm -> FirmwareUi(
        versionChange = Labels.versionChange(u.currentVersion, u.newVersion),
        texts = listOf(Texts.text(UiMessage(MessageId.WARN_OTA_RESTART))),
        progress = null,
        // Potwierdzenie tylko przy dostępnej aktualizacji (np. po utracie łącza przycisk jest nieaktywny).
        actions = listOf(FirmwareAction.UPDATE, FirmwareAction.CANCEL),
    )
    is FirmwareUpdate.Transferring -> FirmwareUi(null, texts(u), u.percent / 100f, listOf(FirmwareAction.CANCEL_UPDATE))
    is FirmwareUpdate.EndRejected -> FirmwareUi(null, texts(u), 1f, listOf(FirmwareAction.RETRY_END, FirmwareAction.CANCEL_UPDATE))
    FirmwareUpdate.Rebooting -> FirmwareUi(null, texts(u), null, emptyList())
    is FirmwareUpdate.Finished -> FirmwareUi(null, texts(u), null, listOf(FirmwareAction.CLOSE))
}

private fun texts(u: FirmwareUpdate): List<String> = listOfNotNull(Messages.firmwareUpdate(u)).map(Texts::text)

/** Przycisk „Aktualizuj” w potwierdzeniu: aktywny przy sesji READY z obsługą OTA (domena i tak sprawdza). */
fun firmwareConfirmEnabled(s: GateUiState): Boolean =
    s.firmwareUpdate is FirmwareUpdate.Confirm && s.connection == ConnectionState.READY

/** W ustawieniach: aktualizacja w toku lub z wynikiem — przycisk prowadzi do jej ekranu zamiast wyboru pliku. */
fun firmwareInProgress(s: GateUiState): Boolean = s.firmwareUpdate != null
