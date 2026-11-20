package com.agendek.bramav2.domain

import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase.DISARMED
import com.agendek.bramav2.protocol.CommandAck
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Testy odtwarzające ustalenia dwunastego przeglądu `:domain`. */
@OptIn(ExperimentalCoroutinesApi::class)
class TwelfthReviewFixesTest {

    private val validQr = "brama://provision?v=2&id=${FakeCredentialStore.DEVICE_ID.toHex()}&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x44 }) + "&p=482915"

    private suspend fun TestScope.ready(h: Harness = Harness(backgroundScope)): Harness {
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED)))
        runCurrent()
        h.link.calls.clear()
        return h
    }

    private fun connects(h: Harness) = h.link.calls.count { it == "connect" }

    @Test
    fun mismatchedCommandResultIsUnknownAndEndsSession() = runTest {
        val h = ready()
        h.repo.open() // OPEN(false)
        runCurrent()
        val wrong = SessionRequest.Open(true)
        h.link.emit(LinkEvent.CommandResult(wrong, CommandAck(MessageType.OPEN.code, AckResult.OUTPUT_TRIGGERED, 1, snapshot(DISARMED, revision = 2)), true))
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
        assertTrue("disconnect" in h.link.calls)
    }

    @Test
    fun mismatchedCommandUnknownIsUnknownToo() = runTest {
        val h = ready()
        h.repo.open()
        runCurrent()
        h.link.emit(LinkEvent.CommandUnknown(SessionRequest.Close))
        runCurrent()
        assertEquals(CommandOutcome.OUTCOME_UNKNOWN, h.state.lastCommand!!.outcome)
    }

    @Test
    fun shortRetryGrowsUntilReady() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, connects(h))
        h.link.emit(LinkEvent.Disconnected) // druga nieudana próba → 4 s
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, connects(h))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, connects(h))
        h.link.emit(LinkEvent.Ready(snapshot(DISARMED))) // sukces zeruje odstęp
        runCurrent()
        h.link.emit(LinkEvent.SessionLost)
        runCurrent()
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(5, connects(h))
    }

    @Test
    fun shortRetryIsCappedAtOneMinute() = runTest {
        val h = Harness(backgroundScope)
        h.repo.start()
        h.repo.uiAttached(UiSource.PHONE)
        runCurrent()
        repeat(8) {
            h.link.emit(LinkEvent.Disconnected)
            runCurrent()
            advanceTimeBy(60_001)
            runCurrent()
        }
        val before = connects(h)
        h.link.emit(LinkEvent.Disconnected)
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(before, connects(h))
        advanceTimeBy(2)
        runCurrent()
        assertEquals(before + 1, connects(h))
    }

    @Test
    fun bluetoothPermissionRevokedWhileConnectedTearsDown() = runTest {
        val h = ready()
        h.repo.reportSystemProblem(SystemProblem.BLUETOOTH_PERMISSION_MISSING)
        runCurrent()
        assertTrue("disconnect" in h.link.calls)
        assertEquals(ConnectionState.DISCONNECTED, h.state.connection)
    }

    @Test
    fun dismissingConfirmingRegistrationReevaluatesLink() = runTest {
        val h = Harness(backgroundScope, credentials = FakeCredentialStore(CredentialLoad.None))
        h.repo.start()
        runCurrent()
        h.repo.startRegistration(validQr)
        runCurrent()
        h.link.emit(LinkEvent.GrantReceived(ByteArray(32) { 0x66 }, FakeCredentialStore.DEVICE_ID.copyOf()))
        h.link.emit(LinkEvent.PairingDone)
        runCurrent()
        assertEquals("connect", h.link.calls.last()) // łącze tylko dla potwierdzenia
        h.repo.cancelRegistration()
        runCurrent()
        assertEquals("disconnect", h.link.calls.last())
    }

    @Test
    fun disablingAutoCloseClearsBackgroundBlocked() = runTest {
        val h = Harness(backgroundScope, settings = FakeSettingsStore(autoClose = true), keeper = FakeConnectionKeeper(allowed = false))
        ready(h)
        h.repo.open()
        runCurrent()
        assertEquals(SystemProblem.BACKGROUND_START_BLOCKED, h.state.systemProblem)
        h.repo.setAutoCloseDefault(false)
        runCurrent()
        assertNull(h.state.systemProblem)
    }
}
