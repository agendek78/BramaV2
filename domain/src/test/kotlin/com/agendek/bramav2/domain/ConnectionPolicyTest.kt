package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.OwnerRelation
import com.agendek.bramav2.protocol.RuleReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPolicyTest {

    private val policy = ConnectionPolicy()

    @Test
    fun noReasonNoConnection() {
        assertEquals(false, policy.shouldConnect)
    }

    @Test
    fun phoneAttachConnects() {
        policy.attach(UiSource.PHONE)
        assertEquals(true, policy.shouldConnect)
    }

    @Test
    fun carAttachConnects() {
        policy.attach(UiSource.CAR)
        assertEquals(true, policy.shouldConnect)
    }

    @Test
    fun detachAllWithoutOwnRuleDisconnects() {
        policy.attach(UiSource.PHONE)
        policy.attach(UiSource.CAR)
        policy.detach(UiSource.PHONE)
        policy.detach(UiSource.CAR)
        assertEquals(false, policy.shouldConnect)
    }

    @Test
    fun ownArmedRuleKeepsLinkWithoutUi() {
        for (s in listOf(
            snapshot(AutoClosePhase.MONITORING),
            snapshot(AutoClosePhase.WAITING, reason = RuleReason.LINK_LOST, remainingMs = 1000),
            snapshot(AutoClosePhase.BOOT_WAIT, reason = RuleReason.REBOOT, remainingMs = 1000),
            snapshot(AutoClosePhase.BLOCKED, reason = RuleReason.INTERLOCK),
        )) {
            policy.updateOwnerArmed(s)
            assertEquals(s.phase.name, true, policy.shouldConnect)
        }
        for (s in listOf(
            snapshot(AutoClosePhase.FAULT, reason = RuleReason.IO_FAULT),
            snapshot(AutoClosePhase.BLOCKED, OwnerRelation.OTHER, reason = RuleReason.INTERLOCK),
            snapshot(AutoClosePhase.MONITORING, OwnerRelation.OTHER),
        )) {
            policy.updateOwnerArmed(s)
            assertEquals(s.phase.name, false, policy.shouldConnect)
        }
        policy.updateOwnerArmed(null)
        assertEquals(false, policy.shouldConnect)
    }

    @Test
    fun userDisconnectSuspendsAllReasons() {
        policy.attach(UiSource.PHONE)
        policy.attach(UiSource.CAR)
        policy.updateOwnerArmed(snapshot(AutoClosePhase.MONITORING))
        policy.suspendByUser()
        assertEquals(false, policy.shouldConnect)
        assertEquals(true, policy.suspended)
        policy.attach(UiSource.PHONE)
        assertEquals(false, policy.shouldConnect)
        policy.resumeByUser()
        assertEquals(true, policy.shouldConnect)
        assertEquals(false, policy.suspended)
    }

    @Test
    fun backoffDoublesToFiveMinutes() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), List(6) { _ -> policy.onRetryableReject() })
        policy.onReady()
        assertEquals(30_000L, policy.onRetryableReject())
        policy.onRetryableReject()
        policy.resumeByUser()
        assertEquals(30_000L, policy.onRetryableReject())
    }

    @Test
    fun terminalRejectBlocksUntilUserAction() {
        policy.attach(UiSource.PHONE)
        policy.onTerminalReject()
        assertEquals(false, policy.shouldConnect)
        policy.attach(UiSource.CAR)
        assertEquals(false, policy.shouldConnect)
        policy.resumeByUser()
        assertEquals(true, policy.shouldConnect)
    }

    @Test
    fun confirmingRegistrationIsAReasonUnlessSuspended() {
        policy.setConfirming(true)
        assertEquals(true, policy.shouldConnect)
        policy.suspendByUser()
        assertEquals(false, policy.shouldConnect)
    }

    @Test
    fun pendingCommandKeepsButDoesNotOpenLink() {
        policy.setCommandPending(true)
        assertEquals(false, policy.shouldConnect)
        assertEquals(true, policy.shouldKeepLink)
        policy.suspendByUser() // świadome „Rozłącz” wygrywa
        assertEquals(false, policy.shouldKeepLink)
    }

    @Test
    fun presenceIsConnectReason() {
        val p = ConnectionPolicy()
        p.setPresence(true)
        assertEquals(true, p.shouldConnect)
        p.setPresence(false)
        assertEquals(false, p.shouldConnect)
    }

    @Test
    fun suppressedOwnerArmedIsNotConnectReason() {
        val p = ConnectionPolicy()
        p.updateOwnerArmed(RuleSnapshotFixtures.ownMonitoring)
        p.setOwnerArmedSuppressed(true)
        assertEquals(false, p.shouldConnect)
        p.setOwnerArmedSuppressed(false)
        assertEquals(true, p.shouldConnect)
    }

    @Test
    fun attachedUiConnectsWhileSuppressed() {
        val p = ConnectionPolicy()
        p.updateOwnerArmed(RuleSnapshotFixtures.ownMonitoring)
        p.setOwnerArmedSuppressed(true)
        p.attach(UiSource.PHONE)
        assertEquals(true, p.shouldConnect)
    }

    @Test
    fun liftUserSuspensionKeepsTerminalBlock() {
        val p = ConnectionPolicy()
        p.setPresence(true)
        p.suspendByUser()
        p.liftUserSuspension()
        assertEquals(true, p.shouldConnect)
        p.onTerminalReject()
        p.liftUserSuspension()
        assertEquals(false, p.shouldConnect)
    }

    private object RuleSnapshotFixtures {
        val ownMonitoring = snapshot(AutoClosePhase.MONITORING, OwnerRelation.SELF)
    }
}
