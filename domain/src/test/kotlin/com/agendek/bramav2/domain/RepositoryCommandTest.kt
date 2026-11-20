package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.FAULT
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.RuleReason
import com.agendek.bramav2.protocol.RuleSnapshot
import com.agendek.bramav2.protocol.SessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryCommandTest {

    private suspend fun TestScope.readyHarness(
        h: Harness = Harness(backgroundScope),
        s: RuleSnapshot = snapshot(DISARMED),
    ): Harness {
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun ack(request: SessionRequest, result: AckResult, s: RuleSnapshot) = CommandAck(
        when (request) {
            is SessionRequest.Open -> MessageType.OPEN.code
            SessionRequest.Close -> MessageType.CLOSE.code
            SessionRequest.Ping -> MessageType.PING.code
            else -> error("nie komenda: $request")
        },
        result, 1, s,
    )

    private fun sends(h: Harness) = h.link.calls.filter { it.startsWith("send:") }

    @Test
    fun openSendsCurrentDefaultAndLocksControls() = runTest {
        val h = readyHarness()
        assertEquals(true, h.state.controlsEnabled)
        h.repo.open()
        runCurrent()
        assertEquals(listOf("send:OPEN(false)"), sends(h))
        assertEquals(false, h.state.controlsEnabled)
        assertEquals(CommandOutcome.IN_FLIGHT, h.state.lastCommand!!.outcome)
    }

    @Test
    fun openBeforeReadyIsIgnored() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.open()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(emptyList<String>(), sends(h))
    }

    @Test
    fun secondIntentWhileInFlightIsRejected() = runTest {
        val h = readyHarness()
        h.repo.open()
        h.repo.open()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals(listOf("send:OPEN(false)"), sends(h))
    }

    @Test
    fun requestCloseSendsCloseImmediately() = runTest {
        val h = readyHarness()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals(listOf("send:CLOSE"), sends(h))
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun requestCloseFromCarSendsImmediately() = runTest {
        val h = readyHarness()
        h.repo.requestClose(UiSource.CAR)
        runCurrent()
        assertEquals(listOf("send:CLOSE"), sends(h))
        assertTrue(h.state.confirmations.isEmpty())
    }

    @Test
    fun requestCloseDuringPendingCommandIsIgnored() = runTest {
        val h = readyHarness()
        h.repo.requestClose(UiSource.PHONE)
        h.repo.requestClose(UiSource.CAR)
        runCurrent()
        assertEquals(listOf("send:CLOSE"), sends(h))
    }

    @Test
    fun closeAllowedWithStaleOrFaultRule() = runTest {
        val h = readyHarness(s = snapshot(FAULT, reason = RuleReason.IO_FAULT))
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        assertEquals(listOf("send:CLOSE"), sends(h))
    }

    @Test
    fun ackOutputTriggeredRecordsResultAndHistory() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true))
        readyHarness(h)
        h.repo.open()
        runCurrent()
        val req = SessionRequest.Open(true)
        h.link.emit(LinkEvent.CommandResult(req, ack(req, AckResult.OUTPUT_TRIGGERED, snapshot(MONITORING, revision = 2)), true))
        runCurrent()
        assertEquals(CommandOutcome.OUTPUT_TRIGGERED, h.state.lastCommand!!.outcome)
        assertTrue(h.state.messages.any { it.id == MessageId.RESULT_OPEN_TRIGGERED })
        assertEquals(1, h.history.appended.size)
        assertEquals(listOf(200), h.history.trims)
        assertEquals(true, h.state.controlsEnabled)
        assertEquals(MONITORING, h.state.rule.snapshot!!.phase)
    }

    @Test
    fun rejectedAckUsesSnapshotNotResultForRule() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true))
        readyHarness(h)
        h.repo.open()
        runCurrent()
        val req = SessionRequest.Open(true)
        val fault = snapshot(FAULT, reason = RuleReason.IO_FAULT, revision = 2)
        h.link.emit(LinkEvent.CommandResult(req, ack(req, AckResult.IO_FAULT, fault), true))
        runCurrent()
        assertEquals(CommandOutcome.REJECTED, h.state.lastCommand!!.outcome)
        assertEquals(AckResult.IO_FAULT, h.state.lastCommand!!.ackResult)
        assertEquals(FAULT, h.state.rule.snapshot!!.phase)
    }

    @Test
    fun unknownOutcomeIsNotRetried() = runTest {
        val h = readyHarness()
        h.repo.requestClose(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Close))
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
        assertTrue(h.state.messages.any { it.id == MessageId.RESULT_UNKNOWN })
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(listOf("send:CLOSE"), sends(h))
        assertEquals(1, h.history.appended.size)
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.history.appended[0].outcome)
    }

    @Test
    fun linkLostDuringCommandIsUnknown() = runTest {
        val h = readyHarness()
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
    }

    @Test
    fun pingDoesNotDisableControlsAndCommandWaits() = runTest {
        val h = readyHarness()
        h.repo.refresh()
        runCurrent()
        assertEquals(listOf("send:PING"), sends(h))
        assertEquals(true, h.state.controlsEnabled)
        h.repo.open()
        runCurrent()
        assertEquals(listOf("send:PING"), sends(h))
        h.link.emit(LinkEvent.Pong(snapshot(DISARMED), applied = false))
        runCurrent()
        assertEquals(listOf("send:PING", "send:OPEN(false)"), sends(h))
    }

    @Test
    fun unknownPingIsNotHistory() = runTest {
        val h = readyHarness()
        h.repo.refresh()
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Ping))
        runCurrent()
        assertEquals(0, h.history.appended.size)
        assertEquals(null, h.state.lastCommand)
        assertEquals(false, h.state.rule.fresh)
    }

    @Test
    fun queuedCommandBehindLostPingIsNotSentAndNotUnknown() = runTest {
        val h = readyHarness()
        h.repo.refresh()
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Ping))
        runCurrent()
        assertEquals(listOf("send:PING"), sends(h))
        assertEquals(null, h.state.lastCommand)
    }

    @Test
    fun openTrueRequiresBackgroundConnection() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true), keeper = FakeConnectionKeeper(allowed = false))
        readyHarness(h)
        h.repo.open()
        runCurrent()
        assertEquals(emptyList<String>(), sends(h))
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        assertEquals(true, h.settings.autoClose.value)
    }

    @Test
    fun strayCommandResultIsIgnored() = runTest {
        val h = readyHarness()
        val req = SessionRequest.Close
        h.link.emit(LinkEvent.CommandResult(req, ack(req, AckResult.OUTPUT_TRIGGERED, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals(null, h.state.lastCommand)
        assertEquals(0, h.history.appended.size)
    }

    @Test
    fun changingDefaultDuringOpenKeepsSentValue() = runTest {
        val h = readyHarness()
        h.repo.open()
        runCurrent()
        h.settings.setAutoCloseDefault(true)
        runCurrent()
        val req = SessionRequest.Open(false)
        h.link.emit(LinkEvent.CommandResult(req, ack(req, AckResult.OUTPUT_TRIGGERED, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals(GateCommand.Open(false), h.state.lastCommand!!.command)
        assertEquals(GateCommand.Open(false), h.history.appended.single().command)
        assertEquals(true, h.state.nextOpenAutoClose)
    }
}
