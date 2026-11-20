package com.agendek.bramav2.domain

import com.agendek.bramav2.domain.MessageArgs.FaultCause
import com.agendek.bramav2.domain.MessageArgs.Seconds
import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.BLOCKED
import com.agendek.bramav2.protocol.AutoClosePhase.BOOT_WAIT
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.FAULT
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.AutoClosePhase.WAITING
import com.agendek.bramav2.protocol.HandshakeRejectReason
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {

    private fun fresh(s: com.agendek.bramav2.protocol.RuleSnapshot) = RuleView(s, fresh = true, refreshing = false)

    @Test
    fun connectionMapping() {
        assertEquals(UiMessage(MessageId.CONNECTED), Messages.connection(ConnectionState.READY))
        for (s in listOf(ConnectionState.DISCONNECTED, ConnectionState.ERROR)) {
            assertEquals(UiMessage(MessageId.NOT_CONNECTED), Messages.connection(s))
        }
        for (s in listOf(
            ConnectionState.SCANNING, ConnectionState.CONNECTING, ConnectionState.SECURING_LINK,
            ConnectionState.AUTHENTICATING, ConnectionState.REKEYING,
        )) {
            assertEquals(UiMessage(MessageId.CONNECTING), Messages.connection(s))
        }
    }

    @Test
    fun freshRuleMapping() {
        assertEquals(UiMessage(MessageId.RULE_NONE), Messages.rule(fresh(snapshot(DISARMED))))
        assertEquals(UiMessage(MessageId.RULE_REBOOT_DISARMED), Messages.rule(fresh(snapshot(DISARMED, reason = RuleReason.REBOOT))))
        assertEquals(UiMessage(MessageId.RULE_MONITORING_SELF), Messages.rule(fresh(snapshot(MONITORING))))
        assertEquals(UiMessage(MessageId.RULE_MONITORING_OTHER), Messages.rule(fresh(snapshot(MONITORING, OwnerRelation.OTHER))))
        assertEquals(
            UiMessage(MessageId.RULE_WAITING, Seconds(30)),
            Messages.rule(fresh(snapshot(WAITING, reason = RuleReason.LINK_LOST, remainingMs = 29_001))),
        )
        assertEquals(
            UiMessage(MessageId.RULE_BOOT_WAIT, Seconds(30)),
            Messages.rule(fresh(snapshot(BOOT_WAIT, reason = RuleReason.REBOOT, remainingMs = 30_000))),
        )
        assertEquals(UiMessage(MessageId.RULE_BLOCKED), Messages.rule(fresh(snapshot(BLOCKED, reason = RuleReason.INTERLOCK))))
        for (r in listOf(RuleReason.IO_FAULT, RuleReason.STORAGE_FAILURE, RuleReason.BUSY_TIMEOUT)) {
            assertEquals(UiMessage(MessageId.RULE_FAULT, FaultCause(r)), Messages.rule(fresh(snapshot(FAULT, reason = r))))
        }
    }

    @Test
    fun staleRuleShowsOnlyStaleness() {
        val fault = snapshot(FAULT, reason = RuleReason.IO_FAULT)
        assertEquals(UiMessage(MessageId.RULE_STALE), Messages.rule(RuleView(fault, fresh = false, refreshing = false)))
        assertEquals(UiMessage(MessageId.RULE_REFRESHING), Messages.rule(RuleView(fault, fresh = false, refreshing = true)))
        assertEquals(UiMessage(MessageId.RULE_STALE), Messages.rule(RuleView(null, fresh = false, refreshing = false)))
    }

    @Test
    fun ackMapping() {
        val open = GateCommand.Open(true)
        fun result(cmd: GateCommand, outcome: CommandOutcome, ack: AckResult?) = Messages.commandResult(LastCommand(cmd, outcome, ack))
        assertEquals(UiMessage(MessageId.RESULT_OPEN_TRIGGERED), result(open, CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED))
        assertEquals(UiMessage(MessageId.RESULT_CLOSE_TRIGGERED), result(GateCommand.Close, CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED))
        val rejected = mapOf(
            AckResult.BUSY to MessageId.RESULT_BUSY,
            AckResult.LEASE_OWNED_BY_OTHER to MessageId.RESULT_LEASE_OWNED_BY_OTHER,
            AckResult.INTERLOCK_BLOCKED to MessageId.RESULT_INTERLOCK_BLOCKED,
            AckResult.IO_FAULT to MessageId.RESULT_IO_FAULT,
            AckResult.STORAGE_FAILURE to MessageId.RESULT_COMPATIBILITY_ERROR,
            AckResult.INVALID_ARGUMENT to MessageId.RESULT_COMPATIBILITY_ERROR,
            AckResult.UNSUPPORTED_TYPE to MessageId.RESULT_COMPATIBILITY_ERROR,
        )
        rejected.forEach { (ack, id) -> assertEquals(ack.name, UiMessage(id), result(open, CommandOutcome.REJECTED, ack)) }
        assertEquals(UiMessage(MessageId.RESULT_UNKNOWN), result(open, CommandOutcome.OUTCOME_UNKNOWN, null))
        assertNull(result(open, CommandOutcome.IDLE, null))
        assertNull(result(open, CommandOutcome.IN_FLIGHT, null))
    }

    @Test
    fun everyAckResultIsMapped() {
        for (ack in AckResult.entries) {
            val outcome = if (ack == AckResult.OUTPUT_TRIGGERED) CommandOutcome.OUTPUT_TRIGGERED else CommandOutcome.REJECTED
            assertNotNull(ack.name, Messages.commandResult(LastCommand(GateCommand.Close, outcome, ack)))
        }
    }

    @Test
    fun everyRejectReasonIsMapped() {
        for (r in HandshakeRejectReason.entries) {
            assertEquals(UiMessage(MessageId.valueOf("REJECT_${r.name}")), Messages.handshakeReject(r))
        }
    }

    @Test
    fun everySystemProblemIsMapped() {
        for (p in SystemProblem.entries) {
            assertEquals(UiMessage(MessageId.valueOf(p.name)), Messages.systemProblem(p))
        }
    }

    @Test
    fun nextOpenLabelAndSecondsRounding() {
        assertEquals(UiMessage(MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_ON), Messages.nextOpenLabel(true))
        assertEquals(UiMessage(MessageId.LABEL_NEXT_OPEN_AUTO_CLOSE_OFF), Messages.nextOpenLabel(false))
        assertEquals(0L, Messages.secondsFrom(0))
        assertEquals(1L, Messages.secondsFrom(1))
        assertEquals(30L, Messages.secondsFrom(30_000))
        assertEquals(0L, Messages.secondsFrom(-5_000)) // zabezpieczenie: nigdy ujemne sekundy
    }
}
