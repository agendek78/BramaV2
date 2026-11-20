package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.OtaAck
import com.agendek.bramav2.protocol.OtaResult
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aktualizacja firmware przez BLE (spec 15.5, ota_design §5). */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareUpdateTest {

    private val info = LinkInfo(firmwareVersion = "0.1.0", otaSupported = true, mtu = 247)

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope), linkInfo: LinkInfo = info, attach: Boolean = true): Harness {
        h.repo.start()
        runCurrent()
        if (attach) h.repo.uiAttached(UiSource.PHONE) else h.repo.presenceAppeared()
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), linkInfo))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    /** Wybór pliku i potwierdzenie — wysyła BEGIN. */
    private fun TestScope.start(h: Harness, image: ByteArray = testImage()) {
        h.repo.selectFirmware(image)
        runCurrent()
        h.repo.confirmFirmwareUpdate()
        runCurrent()
    }

    private fun sends(h: Harness) = h.link.calls.filter { it.startsWith("send:") }

    private fun typeOf(r: SessionRequest): Int = when (r) {
        is SessionRequest.OtaBegin -> MessageType.OTA_BEGIN.code
        is SessionRequest.OtaData -> MessageType.OTA_DATA.code
        SessionRequest.OtaEnd -> MessageType.OTA_END.code
        SessionRequest.OtaAbort -> MessageType.OTA_ABORT.code
        else -> error("nie OTA: $r")
    }

    /** OTA_ACK dla ostatnio wysłanego żądania OTA. */
    private suspend fun TestScope.ackOta(h: Harness, result: OtaResult = OtaResult.OK, nextOffset: Long? = null) {
        val request = h.link.sent.last { it is SessionRequest.OtaBegin || it is SessionRequest.OtaData || it == SessionRequest.OtaEnd || it == SessionRequest.OtaAbort }
        val next = nextOffset ?: when (request) {
            is SessionRequest.OtaData -> request.offset + request.data.size
            else -> 0L
        }
        h.link.emit(LinkEvent.OtaResult(request, OtaAck(typeOf(request), result, 1, next)))
        runCurrent()
    }

    /** Pełny transfer do Rebooting (BEGIN, 4 × DATA, END). */
    private suspend fun TestScope.transferToRebooting(h: Harness) {
        start(h)
        repeat(6) { ackOta(h) }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun availableOnlyWithOtaCapableReadySession() = runTest {
        assertTrue(ready().state.firmwareUpdateAvailable)
        assertEquals("0.1.0", ready().state.firmwareVersion)
        assertFalse(ready(linkInfo = info.copy(otaSupported = false)).state.firmwareUpdateAvailable)
    }

    @Test
    fun invalidFileIsRejectedWithoutSending() = runTest {
        val h = ready()
        h.repo.selectFirmware(ByteArray(100))
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_FILE_INVALID)), h.state.firmwareUpdate)
        assertEquals(emptyList<String>(), sends(h))
    }

    @Test
    fun validFileAsksForConfirmationWithVersions() = runTest {
        val h = ready()
        h.repo.selectFirmware(testImage())
        runCurrent()
        assertEquals(FirmwareUpdate.Confirm(currentVersion = "0.1.0", newVersion = "0.2.0"), h.state.firmwareUpdate)
        assertEquals(emptyList<String>(), sends(h))
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        assertNull(h.state.firmwareUpdate)
    }

    @Test
    fun smallMtuFailsWithoutBegin() = runTest {
        val h = ready(linkInfo = info.copy(mtu = 185))
        start(h)
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_LINK_TOO_SMALL)), h.state.firmwareUpdate)
        assertEquals(emptyList<String>(), sends(h))
    }

    @Test
    fun fullTransferSendsChunksInOrderThenRebootsToNewVersion() = runTest {
        val h = ready()
        start(h)
        assertEquals(listOf("send:OTA_BEGIN(1604)"), sends(h))
        assertEquals(FirmwareUpdate.Transferring(0), h.state.firmwareUpdate)
        assertFalse(h.state.firmwareUpdateAvailable)
        repeat(5) { ackOta(h) }
        assertEquals(
            listOf("send:OTA_BEGIN(1604)", "send:OTA_DATA(0,470)", "send:OTA_DATA(470,470)", "send:OTA_DATA(940,470)", "send:OTA_DATA(1410,194)", "send:OTA_END"),
            sends(h),
        )
        assertEquals(FirmwareUpdate.Transferring(100), h.state.firmwareUpdate)
        ackOta(h)
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
        assertTrue(UiMessage(MessageId.OTA_REBOOTING) in h.state.messages)

        h.link.emit(LinkEvent.Disconnected) // restart sterownika
        runCurrent()
        assertTrue(h.link.calls.count { it == "connect" } >= 1)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info.copy(firmwareVersion = "0.2.0")))
        runCurrent()
        val done = UiMessage(MessageId.OTA_DONE, MessageArgs.Version("0.2.0"))
        assertEquals(FirmwareUpdate.Finished(done), h.state.firmwareUpdate)
        assertEquals(done, h.state.notification)
        assertEquals("0.2.0", h.state.firmwareVersion)
        h.repo.dismissFirmwareUpdate()
        runCurrent()
        assertNull(h.state.firmwareUpdate)
    }

    @Test
    fun progressIsPublished() = runTest {
        val h = ready()
        start(h)
        ackOta(h) // BEGIN
        ackOta(h) // 470 / 1604
        assertEquals(FirmwareUpdate.Transferring(29), h.state.firmwareUpdate)
        assertTrue(UiMessage(MessageId.OTA_PROGRESS, MessageArgs.Percent(29)) in h.state.messages)
    }

    @Test
    fun otherVersionAfterRestartIsRollback() = runTest {
        val h = ready()
        transferToRebooting(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info))
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ROLLED_BACK, MessageArgs.Version("0.1.0"))), h.state.firmwareUpdate)
    }

    @Test
    fun unknownVersionAfterRestartEndsSilently() = runTest {
        val h = ready()
        transferToRebooting(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info.copy(firmwareVersion = null)))
        runCurrent()
        assertNull(h.state.firmwareUpdate)
    }

    @Test
    fun noReadyWithin90sIsReported() = runTest {
        val h = ready()
        transferToRebooting(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        wait(89_000)
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
        wait(2_000)
        val message = UiMessage(MessageId.OTA_NO_RECONNECT)
        assertEquals(FirmwareUpdate.Finished(message), h.state.firmwareUpdate)
        assertEquals(message, h.state.notification)
        // Spóźnione READY nie zmienia wyniku.
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info.copy(firmwareVersion = "0.2.0")))
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(message), h.state.firmwareUpdate)
    }

    @Test
    fun commandBetweenChunks() = runTest {
        val h = ready()
        start(h)
        ackOta(h) // BEGIN → DATA(0) w toku
        assertTrue(h.state.controlsEnabled) // przyciski działają w trakcie transferu (15.5)
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals("send:OTA_DATA(0,470)", sends(h).last()) // CLOSE czeka na OTA_ACK
        ackOta(h)
        assertEquals("send:CLOSE", sends(h).last()) // przed następnym kawałkiem
        assertFalse(h.state.controlsEnabled)
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 3, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals("send:OTA_DATA(470,470)", sends(h).last())
        assertEquals(CommandOutcome.OUTPUT_TRIGGERED, h.state.lastCommand!!.outcome)
    }

    @Test
    fun pingBetweenChunks() = runTest {
        val h = ready()
        start(h)
        ackOta(h) // BEGIN → DATA(0) w toku
        h.repo.refresh()
        runCurrent()
        assertEquals("send:OTA_DATA(0,470)", sends(h).last())
        ackOta(h)
        assertEquals("send:PING", sends(h).last())
        h.link.emit(LinkEvent.Pong(snapshot(DISARMED, revision = 2), true))
        runCurrent()
        assertEquals("send:OTA_DATA(470,470)", sends(h).last())
        assertTrue(h.state.rule.fresh)
    }

    @Test
    fun sessionLostDuringTransferAborts() = runTest {
        val h = ready()
        start(h)
        ackOta(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
    }

    @Test
    fun keptLinkRecoveryDuringTransferAborts() = runTest {
        val h = ready()
        start(h)
        val begin = h.link.sent.last()
        h.link.emit(LinkEvent.CommandUnknown(begin))
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
        h.link.emit(LinkEvent.Rekeying)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED), info))
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
        assertTrue(sends(h).none { it.startsWith("send:OTA_DATA") })
    }

    @Test
    fun sessionLostAfterEndMeansRebooting() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) } // END wysłany, bez odpowiedzi
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.OtaEnd))
        runCurrent()
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
    }

    @Test
    fun disconnectAfterEndMeansRebooting() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) }
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
    }

    @Test
    fun unexpectedNextOffsetAborts() = runTest {
        val h = ready()
        start(h)
        ackOta(h)
        ackOta(h, nextOffset = 100)
        assertEquals("send:OTA_ABORT", sends(h).last())
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
        ackOta(h) // OTA_ACK na ABORT
        assertEquals("send:OTA_ABORT", sends(h).last())
    }

    @Test
    fun rejectionsMapToSpecTexts() = runTest {
        val cases = listOf(
            OtaResult.BUSY to MessageId.OTA_BUSY,
            OtaResult.RULE_ACTIVE to MessageId.OTA_RULE_ACTIVE,
            OtaResult.LINK_TOO_SMALL to MessageId.OTA_LINK_TOO_SMALL,
            OtaResult.TOO_LARGE to MessageId.OTA_FILE_INVALID,
            OtaResult.IMAGE_REJECTED to MessageId.OTA_IMAGE_REJECTED,
            OtaResult.STORAGE_FAILURE to MessageId.OTA_STORAGE_FAILURE,
            OtaResult.INVALID_ARGUMENT to MessageId.OTA_ABORTED,
        )
        for ((result, id) in cases) {
            val h = ready()
            start(h)
            ackOta(h, result)
            assertEquals(result.name, FirmwareUpdate.Finished(UiMessage(id)), h.state.firmwareUpdate)
            assertEquals(1, sends(h).size)
        }
        val h = ready()
        start(h)
        ackOta(h)
        ackOta(h, OtaResult.DOWNGRADE) // na pierwszy kawałek z nagłówkiem
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_DOWNGRADE)), h.state.firmwareUpdate)
    }

    @Test
    fun endRejectedByActiveRuleCanBeRetried() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) }
        ackOta(h, OtaResult.RULE_ACTIVE)
        assertEquals(FirmwareUpdate.EndRejected(UiMessage(MessageId.OTA_RULE_ACTIVE)), h.state.firmwareUpdate)
        h.repo.retryFirmwareEnd()
        runCurrent()
        assertEquals("send:OTA_END", sends(h).last())
        assertEquals(2, sends(h).count { it == "send:OTA_END" })
        ackOta(h)
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
    }

    @Test
    fun cancelInEndRejectedSendsAbort() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) }
        ackOta(h, OtaResult.BUSY)
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        assertEquals("send:OTA_ABORT", sends(h).last())
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
    }

    @Test
    fun cancelDuringTransferAbortsAfterPendingChunk() = runTest {
        val h = ready()
        start(h)
        ackOta(h) // DATA(0) w toku
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
        assertEquals("send:OTA_DATA(0,470)", sends(h).last()) // jedno żądanie naraz
        ackOta(h)
        assertEquals("send:OTA_ABORT", sends(h).last())
        ackOta(h)
        assertEquals(1, sends(h).count { it == "send:OTA_ABORT" })
        assertEquals(1, sends(h).count { it.startsWith("send:OTA_DATA") })
    }

    @Test
    fun cancelWhileCommandInFlightDefersAbort() = runTest { // przegląd: ABORT obok komendy łamał 13.3
        val h = ready()
        start(h)
        ackOta(h) // DATA(0) w toku
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        ackOta(h) // CLOSE wysłany między kawałkami
        assertEquals("send:CLOSE", sends(h).last())
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        assertEquals("send:CLOSE", sends(h).last()) // jedno żądanie naraz
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 3, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals("send:OTA_ABORT", sends(h).last())
        assertTrue(sends(h).none { it == "send:OTA_DATA(470,470)" })
    }

    @Test
    fun cancelInEndRejectedWithCommandInFlightDefersAbort() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) }
        ackOta(h, OtaResult.RULE_ACTIVE)
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        assertEquals("send:CLOSE", sends(h).last())
        h.link.emit(LinkEvent.CommandResult(SessionRequest.Close, CommandAck(MessageType.CLOSE.code, AckResult.OUTPUT_TRIGGERED, 7, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals("send:OTA_ABORT", sends(h).last())
    }

    @Test
    fun newUpdateWaitsForPendingAbort() = runTest { // przegląd: spóźniony OTA_ACK trafiał do nowego transferu
        val h = ready()
        start(h)
        ackOta(h) // DATA(0) w toku
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        h.repo.dismissFirmwareUpdate()
        runCurrent()
        assertFalse(h.state.firmwareUpdateAvailable)
        h.repo.selectFirmware(testImage())
        runCurrent()
        h.repo.confirmFirmwareUpdate()
        runCurrent()
        assertEquals(1, sends(h).count { it.startsWith("send:OTA_BEGIN") })
        ackOta(h) // OTA_ACK starego DATA → ABORT
        assertEquals("send:OTA_ABORT", sends(h).last())
        ackOta(h) // OTA_ACK na ABORT
        h.repo.confirmFirmwareUpdate() // potwierdzenie czekało na rozstrzygnięcie
        runCurrent()
        assertEquals("send:OTA_BEGIN(1604)", sends(h).last())
        assertEquals(FirmwareUpdate.Transferring(0), h.state.firmwareUpdate)
    }

    @Test
    fun queuedCommandBehindEndIsWithdrawnAfterOk() = runTest { // przegląd: sterownik po END nie odpowiada na komendy
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) } // END w toku
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        ackOta(h)
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
        assertTrue(sends(h).none { it == "send:CLOSE" })
        assertTrue(h.history.appended.isEmpty())
        assertFalse(h.state.controlsEnabled)
    }

    @Test
    fun disconnectAfterEndSentNeedsNoAbortWarning() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) } // END wysłany: obraz mógł zostać przyjęty
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        assertTrue(h.state.confirmations.isEmpty())
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun cancelAfterEndSentIsIgnored() = runTest {
        val h = ready()
        start(h)
        repeat(5) { ackOta(h) }
        h.repo.cancelFirmwareUpdate()
        runCurrent()
        ackOta(h)
        assertEquals(FirmwareUpdate.Rebooting, h.state.firmwareUpdate)
    }

    @Test
    fun disconnectDuringTransferNeedsConfirmation() = runTest {
        val h = ready()
        start(h)
        ackOta(h)
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        assertEquals(Confirmation.DISCONNECT_ABORTS_UPDATE, h.state.confirmations[UiSource.PHONE])
        assertEquals(listOf(UiMessage(MessageId.WARN_DISCONNECT_ABORTS_UPDATE)), h.state.warnings)
        assertTrue("disconnect" !in h.link.calls)
        h.repo.confirmDisconnect(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
        assertTrue(sends(h).none { it == "send:OTA_ABORT" }) // sterownik porzuca transfer z utratą sesji
        assertEquals(FirmwareUpdate.Finished(UiMessage(MessageId.OTA_ABORTED)), h.state.firmwareUpdate)
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun disconnectInRebootingEndsTrackingSilently() = runTest {
        val h = ready()
        transferToRebooting(h)
        h.repo.requestDisconnect(UiSource.PHONE)
        runCurrent()
        assertNull(h.state.firmwareUpdate)
        wait(100_000)
        assertNull(h.state.firmwareUpdate)
    }

    @Test
    fun transferKeepsLinkWithoutPhoneUi() = runTest {
        val h = ready()
        start(h)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
        assertTrue(h.state.backgroundLinkNeeded)
    }

    @Test
    fun rebootingReconnectsWithoutPhoneUi() = runTest {
        val h = ready()
        transferToRebooting(h)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertTrue("connect" in h.link.calls)
        wait(91_000)
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun dismissedDuringTransferKeepsLinkAndTransfer() = runTest {
        val h = ready(attach = false)
        assertEquals(PresenceNotification.SHOWN, h.state.presence)
        start(h)
        h.repo.notificationDismissed()
        runCurrent()
        wait(10 * 60_000L)
        assertEquals(PresenceNotification.QUIET, h.state.presence)
        assertTrue(h.state.firmwareUpdate != null)
        assertTrue("disconnect" !in h.link.calls) // łącze trzyma aktualizacja (spec 4.3 pkt 4)
    }

    @Test
    fun removeFromPhoneClearsUpdate() = runTest {
        val h = ready()
        start(h)
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertNull(h.state.firmwareUpdate)
        assertFalse(h.state.backgroundLinkNeeded)
    }

    @Test
    fun lateOtaResultAfterFinishIsIgnored() = runTest {
        val h = ready()
        start(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        val finished = h.state.firmwareUpdate
        h.link.emit(LinkEvent.OtaResult(h.link.sent.last(), OtaAck(MessageType.OTA_BEGIN.code, OtaResult.OK, 1, 0)))
        runCurrent()
        assertEquals(finished, h.state.firmwareUpdate)
    }

    @Test
    fun selectDuringTransferIsIgnored() = runTest {
        val h = ready()
        start(h)
        h.repo.selectFirmware(testImage(version = "9.9.9"))
        runCurrent()
        assertEquals(FirmwareUpdate.Transferring(0), h.state.firmwareUpdate)
    }

    @Test
    fun carMessagesHaveNoUpdateLine() = runTest {
        val h = ready()
        start(h)
        assertTrue(h.state.carMessages.none { it.id == MessageId.OTA_PROGRESS })
    }

    companion object {
        /**
         * Poprawny obraz ESP-IDF (format jak w `FirmwareImages`): nagłówek 24 B, jeden segment z `esp_app_desc_t`,
         * dopełnienie z sumą kontrolną i blok podpisu 68 B — razem 1604 B dla [dataLen] = 1500.
         */
        fun testImage(version: String = "0.2.0", dataLen: Int = 1500): ByteArray {
            val segmentEnd = 24 + 8 + dataLen
            val imageEnd = (segmentEnd or 15) + 1
            val b = ByteArray(imageEnd + 68)
            b[0] = 0xE9.toByte()
            b[1] = 1
            putLe(b, 24 + 4, dataLen.toLong())
            putLe(b, 32, 0xABCD5432L)
            putLe(b, 32 + 4, 1)
            version.toByteArray().copyInto(b, 32 + 16)
            "brama".toByteArray().copyInto(b, 32 + 48)
            return b
        }

        private fun putLe(b: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) b[offset + i] = (value ushr (8 * i)).toByte()
        }
    }
}
