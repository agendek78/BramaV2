package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.AutoClosePhase.MONITORING
import com.agendek.bramav2.protocol.AutoClosePhase.WAITING
import com.agendek.bramav2.protocol.RuleReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryConnectionTest {

    private fun TestScope.started(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        runCurrent()
        return h
    }

    private suspend fun TestScope.ready(h: Harness, s: com.agendek.bramav2.protocol.RuleSnapshot = snapshot(DISARMED)) {
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(s))
        runCurrent()
    }

    @Test
    fun startWithoutCredentialDoesNotConnect() = runTest {
        val h = started(Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None)))
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(emptyList<String>(), h.link.calls)
        assertEquals(CredentialState.NONE, h.state.credential)
    }

    @Test
    fun lockedCredentialReportsProblem() = runTest {
        val h = started(Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.Locked)))
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(SystemProblem.CREDENTIAL_LOCKED, h.state.systemProblem)
        assertEquals(emptyList<String>(), h.link.calls)
    }

    @Test
    fun phoneAttachConnectsWithActiveCredential() = runTest {
        val h = started()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        assertEquals(listOf("connect"), h.link.calls)
        assertArrayEquals(h.credentials.key, h.link.lastConnectKey)
        assertEquals(CredentialState.ACTIVE, h.state.credential)
    }

    @Test
    fun readyMakesRuleFreshAndConnected() = runTest {
        val h = started()
        ready(h, snapshot(MONITORING))
        assertEquals(ConnectionState.READY, h.state.connection)
        assertTrue(h.state.rule.fresh)
        assertEquals(MessageId.CONNECTED, h.state.messages.first().id)
    }

    @Test
    fun attachAfterReadySendsPingAndMarksRefreshing() = runTest {
        val h = started()
        ready(h)
        h.repo.uiAttached(UiSource.CAR)
        runCurrent()
        assertEquals(true, h.state.rule.refreshing)
        assertTrue("send:PING" in h.link.calls)
        h.link.emit(LinkEvent.Pong(snapshot(DISARMED), applied = false))
        runCurrent()
        assertEquals(false, h.state.rule.refreshing)
        assertEquals(true, h.state.rule.fresh)
    }

    @Test
    fun sessionLostMarksRuleStale() = runTest {
        val h = started()
        ready(h, snapshot(MONITORING))
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        assertEquals(false, h.state.rule.fresh)
        assertTrue(h.state.messages.any { it.id == MessageId.RULE_STALE || it.id == MessageId.RULE_REFRESHING })
    }

    @Test
    fun sessionLostReconnectsWhenReasonExists() = runTest {
        val h = started()
        ready(h)
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        // utracone połączenie jest jawnie kończone, aby kontrakt odciął jego spóźnione zdarzenia
        assertEquals(listOf("connect", "disconnect", "connect"), h.link.calls)
    }

    @Test
    fun detachWithoutOwnRuleDisconnects() = runTest {
        val h = started()
        ready(h)
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertEquals("disconnect", h.link.calls.last())
    }

    @Test
    fun ownArmedRuleKeepsLinkAfterDetach() = runTest {
        val h = started()
        ready(h, snapshot(MONITORING))
        h.repo.uiDetached(UiSource.PHONE)
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
    }

    @Test
    fun carFlappingWhileOwnerArmedNeverDisconnects() = runTest {
        val h = started()
        ready(h, snapshot(MONITORING))
        h.repo.uiDetached(UiSource.PHONE)
        repeat(5) {
            h.repo.uiAttached(UiSource.CAR)
            h.repo.uiDetached(UiSource.CAR)
        }
        runCurrent()
        assertTrue("disconnect" !in h.link.calls)
    }

    @Test
    fun startupWithCachedOwnArmedRuleConnectsWithoutUi() = runTest {
        val cached = snapshot(WAITING, reason = RuleReason.LINK_LOST, remainingMs = 20_000)
        val h = started(Harness(backgroundScope, ruleCache = FakeRuleCacheStore(LastKnownRule(cached, 1))))
        assertEquals(listOf("connect"), h.link.calls)
        assertEquals(cached, h.state.rule.snapshot)
        assertEquals(false, h.state.rule.fresh)
    }

    @Test
    fun rebootDisarmedAfterOwnArmedRaisesNotification() = runTest {
        val h = started(Harness(backgroundScope, ruleCache = FakeRuleCacheStore(LastKnownRule(snapshot(MONITORING), 1))))
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED, reason = RuleReason.REBOOT)))
        runCurrent()
        assertEquals(UiMessage(MessageId.RULE_REBOOT_DISARMED), h.state.notification)
    }

    @Test
    fun rebootDisarmedWithoutPriorOwnRuleHasNoNotification() = runTest {
        val h = started()
        ready(h, snapshot(DISARMED, reason = RuleReason.REBOOT))
        assertNull(h.state.notification)
    }

    @Test
    fun freshSnapshotIsCached() = runTest {
        val h = started()
        ready(h, snapshot(MONITORING))
        assertEquals(MONITORING, h.ruleCache.saved.last().snapshot.phase)
    }

    @Test
    fun systemProblemIsShownFirst() = runTest {
        val h = started()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_OFF)
        runCurrent()
        assertEquals(MessageId.BLUETOOTH_OFF, h.state.messages.first().id)
        h.repo.reportSystemProblem(null)
        runCurrent()
        assertEquals(MessageId.NOT_CONNECTED, h.state.messages.first().id)
    }
}
