package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.FirmwareUpdate
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageArgs
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareModelTest {

    private fun state(update: FirmwareUpdate?, connection: ConnectionState = ConnectionState.READY) =
        GateUiState.INITIAL.copy(firmwareUpdate = update, connection = connection)

    @Test
    fun confirmShowsVersionChangeAndWarning() {
        val ui = firmwareUi(state(FirmwareUpdate.Confirm("0.1.0", "0.2.0")))
        assertEquals("Wersja 0.1.0 → 0.2.0", ui.versionChange)
        assertEquals(listOf(Texts.text(UiMessage(MessageId.WARN_OTA_RESTART))), ui.texts)
        assertEquals(listOf(FirmwareAction.UPDATE, FirmwareAction.CANCEL), ui.actions)
        assertEquals("Wersja — → 0.2.0", firmwareUi(state(FirmwareUpdate.Confirm(null, "0.2.0"))).versionChange)
    }

    @Test
    fun confirmNeedsReadySession() {
        assertTrue(firmwareConfirmEnabled(state(FirmwareUpdate.Confirm("0.1.0", "0.2.0"))))
        assertFalse(firmwareConfirmEnabled(state(FirmwareUpdate.Confirm("0.1.0", "0.2.0"), ConnectionState.REKEYING)))
    }

    @Test
    fun transferShowsProgress() {
        val ui = firmwareUi(state(FirmwareUpdate.Transferring(42)))
        assertEquals(0.42f, ui.progress!!, 0.001f)
        assertEquals(listOf("Przesyłanie firmware: 42%"), ui.texts)
        assertEquals(listOf(FirmwareAction.CANCEL_UPDATE), ui.actions)
    }

    @Test
    fun endRejectedCanBeRetried() {
        val ui = firmwareUi(state(FirmwareUpdate.EndRejected(UiMessage(MessageId.OTA_RULE_ACTIVE))))
        assertEquals(listOf(FirmwareAction.RETRY_END, FirmwareAction.CANCEL_UPDATE), ui.actions)
    }

    @Test
    fun rebootingHasNoActions() {
        val ui = firmwareUi(state(FirmwareUpdate.Rebooting))
        assertEquals(listOf("Sterownik uruchamia się ponownie…"), ui.texts)
        assertTrue(ui.actions.isEmpty())
        assertNull(ui.progress)
    }

    @Test
    fun finishedShowsResultAndClose() {
        val ui = firmwareUi(state(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_DONE, MessageArgs.Version("0.2.0")))))
        assertEquals(listOf("Zaktualizowano firmware do wersji 0.2.0"), ui.texts)
        assertEquals(listOf(FirmwareAction.CLOSE), ui.actions)
    }
}
