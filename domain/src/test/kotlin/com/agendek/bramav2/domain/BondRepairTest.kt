package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.HandshakeRejectReason
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

/** Naprawa bondu przy zachowanym `K_client` (spec 9.5, bond_repair_design §4.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class BondRepairTest {

    /** Ekran telefonu otwarty, zwykłe `connect` zakończone `BondMissing`. */
    private suspend fun TestScope.bondMissing(): Harness {
        val h = Harness(backgroundScope)
        h.repo.start()
        runCurrent()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.BondMissing)
        runCurrent()
        return h
    }

    private suspend fun TestScope.startRepair(h: Harness) {
        h.link.calls.clear()
        h.repo.repairBond()
        runCurrent()
    }

    @Test
    fun bondMissingOffersRepairAndStopsRetrying() = runTest {
        val h = bondMissing()
        assertEquals(UiMessage(MessageId.BOND_MISSING), h.state.handshakeProblem)
        assertTrue(h.state.bondRepairAvailable)
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
        assertTrue(UiMessage(MessageId.BOND_MISSING) in h.state.messages)
        h.link.calls.clear()
        advanceTimeBy(600_000)
        runCurrent()
        assertFalse("connect" in h.link.calls) // bez pętli łączenia do decyzji użytkownika
    }

    @Test
    fun repairUsesStoredCredentialAndEndsWithNotification() = runTest {
        val h = bondMissing()
        startRepair(h)
        assertEquals(listOf("repairBond"), h.link.calls.filter { it == "repairBond" || it == "connect" })
        assertTrue(h.link.lastConnectKey!!.isNotEmpty())
        assertEquals(ConnectionState.CONNECTING, h.state.connection)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        assertEquals(ConnectionState.READY, h.state.connection)
        assertFalse(h.state.bondRepairAvailable)
        assertNull(h.state.handshakeProblem)
        assertEquals(UiMessage(MessageId.REPAIR_DONE), h.state.notification)
    }

    @Test
    fun bondFailedShowsInstructionAndKeepsRepairAvailable() = runTest {
        val h = bondMissing()
        startRepair(h)
        h.link.emit(LinkEvent.BondFailed)
        runCurrent()
        assertEquals(UiMessage(MessageId.REPAIR_BOND_FAILED), h.state.handshakeProblem)
        assertTrue(h.state.bondRepairAvailable)
        h.link.calls.clear()
        advanceTimeBy(600_000)
        runCurrent()
        assertFalse("connect" in h.link.calls)
        assertFalse("repairBond" in h.link.calls) // naprawa nie jest ponawiana automatycznie
    }

    @Test
    fun repairRejectionsUseTextsFromSpec() = runTest {
        val cases = mapOf(
            HandshakeRejectReason.WINDOW_CLOSED to MessageId.REJECT_WINDOW_CLOSED,
            HandshakeRejectReason.REPLACEMENT_BLOCKED to MessageId.REJECT_REPLACEMENT_BLOCKED,
            HandshakeRejectReason.STORAGE_FAILURE to MessageId.REPAIR_STORAGE_FAILURE,
        )
        for ((reason, id) in cases) {
            val h = bondMissing()
            startRepair(h)
            h.link.emit(LinkEvent.HandshakeRejected(reason))
            runCurrent()
            assertEquals(reason.name, UiMessage(id), h.state.handshakeProblem)
            assertTrue(reason.name, h.state.bondRepairAvailable)
        }
    }

    @Test
    fun repairLostBeforeReadyIsReportedWithoutRetry() = runTest {
        for (event in listOf(LinkEvent.Disconnected, LinkEvent.HandshakeFailed)) {
            val h = bondMissing()
            startRepair(h)
            h.link.emit(event)
            runCurrent()
            assertEquals(event.toString(), UiMessage(MessageId.REPAIR_BOND_FAILED), h.state.handshakeProblem)
            assertTrue(h.state.bondRepairAvailable)
            h.link.calls.clear()
            advanceTimeBy(600_000)
            runCurrent()
            assertTrue(h.link.calls.none { it == "connect" || it == "repairBond" })
        }
    }

    @Test
    fun ordinaryConnectAfterRepairUsesConnect() = runTest {
        val h = bondMissing()
        startRepair(h)
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("connect" in h.link.calls)
        assertFalse("repairBond" in h.link.calls)
    }

    @Test
    fun userConnectClearsBlockAndRetriesOrdinarySession() = runTest {
        val h = bondMissing()
        h.link.calls.clear()
        h.repo.connect()
        runCurrent()
        assertTrue("connect" in h.link.calls)
    }

    @Test
    fun repairIgnoredWithoutCredentialOrDuringRegistration() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.repairBond()
        runCurrent()
        assertFalse("repairBond" in h.link.calls)
    }

    @Test
    fun removeFromPhoneClearsRepairOffer() = runTest {
        val h = bondMissing()
        h.repo.requestRemoveFromPhone()
        runCurrent()
        h.repo.confirmRemoveFromPhone()
        runCurrent()
        assertFalse(h.state.bondRepairAvailable)
        assertNull(h.state.handshakeProblem)
    }
}
