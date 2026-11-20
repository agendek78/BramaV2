package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMessagesTest {

    private val readyMonitoring = GateUiState.INITIAL.copy(
        credential = CredentialState.ACTIVE,
        connection = ConnectionState.READY,
        rule = RuleView(snapshot(AutoClosePhase.MONITORING), fresh = true, refreshing = false),
        lastCommand = LastCommand(GateCommand.Open(true), CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED),
        nextOpenAutoClose = true,
    )

    @Test
    fun phoneOrderFollowsCarPriority() {
        assertEquals(
            listOf(
                MessageId.CONNECTED, MessageId.POSITION_UNKNOWN, MessageId.RULE_MONITORING_SELF,
                MessageId.RESULT_OPEN_TRIGGERED, MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_ON,
            ),
            UiMessages.phone(readyMonitoring).map { it.id },
        )
    }

    @Test
    fun systemProblemComesFirst() {
        val ids = UiMessages.phone(readyMonitoring.copy(systemProblem = SystemProblem.BLUETOOTH_OFF)).map { it.id }
        assertEquals(MessageId.BLUETOOTH_OFF, ids[0])
        assertEquals(MessageId.CONNECTED, ids[1])
    }

    @Test
    fun positionUnknownAlwaysPresent() {
        for (c in ConnectionState.entries) {
            val ids = UiMessages.phone(GateUiState.INITIAL.copy(connection = c, credential = CredentialState.ACTIVE)).map { it.id }
            assertTrue(c.name, ids.indexOf(MessageId.POSITION_UNKNOWN) in 0..2)
        }
    }

    @Test
    fun carWithoutActiveCredentialAsksToConfigureOnPhone() {
        val state = readyMonitoring.copy(credential = CredentialState.UNCONFIRMED)
        assertEquals(listOf(MessageId.CONFIGURE_ON_PHONE, MessageId.POSITION_UNKNOWN), UiMessages.car(state).map { it.id })
        for (p in listOf(SystemProblem.BLUETOOTH_PERMISSION_MISSING, SystemProblem.CREDENTIAL_LOCKED)) {
            assertEquals(
                listOf(MessageId.CONFIGURE_ON_PHONE, MessageId.POSITION_UNKNOWN),
                UiMessages.car(readyMonitoring.copy(systemProblem = p)).map { it.id },
            )
        }
    }

    @Test
    fun carKeepsRuleBeforeResult() {
        val ids = UiMessages.car(readyMonitoring).map { it.id.name }
        assertTrue(ids.indexOfFirst { it.startsWith("RULE_") } < ids.indexOfFirst { it.startsWith("RESULT_") })
    }

    @Test
    fun handshakeProblemFollowsConnection() {
        val state = readyMonitoring.copy(
            connection = ConnectionState.DISCONNECTED,
            handshakeProblem = UiMessage(MessageId.REJECT_UNAVAILABLE),
        )
        assertEquals(
            listOf(MessageId.NOT_CONNECTED, MessageId.REJECT_UNAVAILABLE, MessageId.POSITION_UNKNOWN),
            UiMessages.phone(state).map { it.id }.take(3),
        )
    }

    @Test
    fun carKeepsRuleInFirstThreeEvenWithProblems() {
        val state = readyMonitoring.copy(
            systemProblem = SystemProblem.BLUETOOTH_OFF,
            handshakeProblem = UiMessage(MessageId.REJECT_UNAVAILABLE),
        )
        val ids = UiMessages.car(state).map { it.id }
        assertEquals(listOf(MessageId.CONNECTED, MessageId.POSITION_UNKNOWN, MessageId.RULE_MONITORING_SELF), ids.take(3))
        assertTrue(MessageId.BLUETOOTH_OFF in ids && MessageId.REJECT_UNAVAILABLE in ids)
    }

    @Test
    fun carNotificationConfigureProblemShowsOnlyConfigure() {
        for (problem in listOf(SystemProblem.BLUETOOTH_PERMISSION_MISSING, SystemProblem.CREDENTIAL_LOCKED)) {
            val s = GateUiState.INITIAL.copy(credential = CredentialState.ACTIVE, systemProblem = problem)
            assertEquals(listOf(UiMessage(MessageId.CONFIGURE_ON_PHONE)), UiMessages.carNotification(s))
        }
    }

    @Test
    fun carNotificationIsOnlyConnectionState() {
        val s = GateUiState.INITIAL.copy(
            credential = CredentialState.ACTIVE,
            connection = ConnectionState.DISCONNECTED,
            systemProblem = SystemProblem.BLUETOOTH_OFF,
            handshakeProblem = UiMessage(MessageId.REJECT_WINDOW_CLOSED),
            lastCommand = LastCommand(GateCommand.Open(false), CommandOutcome.REJECTED, com.agendek.bramav2.protocol.AckResult.BUSY),
        )
        assertEquals(listOf(UiMessage(MessageId.NOT_CONNECTED)), UiMessages.carNotification(s))
    }

    @Test
    fun carScreenOmitsPhoneOnlyProblems() {
        val s = GateUiState.INITIAL.copy(credential = CredentialState.ACTIVE, systemProblem = SystemProblem.CAMERA_PERMISSION_MISSING)
        assertTrue(UiMessage(MessageId.CAMERA_PERMISSION_MISSING) !in UiMessages.car(s))
        assertTrue(UiMessage(MessageId.CAMERA_PERMISSION_MISSING) in UiMessages.phone(s))
    }
}
