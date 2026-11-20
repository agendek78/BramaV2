package com.agendek.bramav2.car

import com.agendek.bramav2.domain.CommandOutcome
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.GateCommand
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.LastCommand
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.PresenceNotification
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.domain.UiMessage
import com.agendek.bramav2.protocol.AckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarNotificationPolicyTest {

    private val policy = CarNotificationPolicy()

    private fun s(
        presence: PresenceNotification = PresenceNotification.SHOWN,
        actions: Boolean = true,
        connection: ConnectionState = ConnectionState.READY,
        last: LastCommand? = null,
    ) = GateUiState.INITIAL.copy(
        credential = com.agendek.bramav2.domain.CredentialState.ACTIVE, // zarejestrowany telefon
        gateName = "Wjazd", presence = presence, notificationActionsAvailable = actions, connection = connection, lastCommand = last,
    )

    private fun openDone(result: AckResult? = AckResult.OUTPUT_TRIGGERED, outcome: CommandOutcome = CommandOutcome.OUTPUT_TRIGGERED) =
        LastCommand(GateCommand.Open(false), outcome, result)

    @Test
    fun showsWhenShownAndProjecting() {
        val n = policy.update(s(), projecting = true)!!
        assertEquals("Wjazd", n.title)
        assertTrue(Texts.text(UiMessage(MessageId.CONNECTED)) in n.lines)
        assertEquals(1, n.lines.size)
    }

    @Test
    fun hiddenWithoutProjection() {
        assertNull(policy.update(s(), projecting = false))
    }

    @Test
    fun openButtonOnlyWhenActionsAvailable() {
        assertTrue(policy.update(s(), true)!!.showOpen)
        assertFalse(policy.update(s(actions = false), true)!!.showOpen)
    }

    @Test
    fun confirmedOpenInCycleHides() {
        policy.update(s(), true)
        assertNull(policy.update(s(last = openDone()), true))
        assertNull(policy.update(s(last = openDone()), true)) // zostaje ukryte do końca pobytu
    }

    @Test
    fun closeResultDoesNotHide() {
        policy.update(s(), true)
        val close = LastCommand(GateCommand.Close, CommandOutcome.OUTPUT_TRIGGERED, AckResult.OUTPUT_TRIGGERED)
        assertNotNull(policy.update(s(last = close), true))
    }

    @Test
    fun busyOrUnknownKeepsNotificationWithoutResultText() {
        policy.update(s(), true)
        val busy = policy.update(s(last = openDone(AckResult.BUSY, CommandOutcome.REJECTED)), true)!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.CONNECTED))), busy.lines)
        assertTrue(busy.showOpen) // przycisk zostaje: można spróbować ponownie
        val unknown = policy.update(s(last = openDone(null, CommandOutcome.OUTCOME_UNKNOWN)), true)!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.CONNECTED))), unknown.lines)
    }

    @Test
    fun previousCycleResultDoesNotHideNewCycle() {
        val previous = openDone()
        policy.update(s(presence = PresenceNotification.HIDDEN, last = previous), true)
        val n = policy.update(s(last = previous), true)!!
        assertFalse(Texts.text(UiMessage(MessageId.RESULT_OPEN_TRIGGERED)) in n.lines)
    }

    @Test
    fun newCycleAfterHiddenShowsAgain() {
        policy.update(s(), true)
        val done = openDone()
        assertNull(policy.update(s(last = done), true))
        policy.update(s(presence = PresenceNotification.HIDDEN, last = done), true)
        assertNotNull(policy.update(s(last = done), true))
    }

    @Test
    fun transientLinkLossKeepsNotificationWithoutButton() {
        policy.update(s(), true)
        val lost = policy.update(s(actions = false, connection = ConnectionState.DISCONNECTED), true)!!
        assertFalse(lost.showOpen)
        assertTrue(policy.update(s(), true)!!.showOpen)
    }

    @Test
    fun projectionEndHidesWithoutTouchingCommand() {
        val inFlight = LastCommand(GateCommand.Open(true), CommandOutcome.IN_FLIGHT, null)
        policy.update(s(), true)
        assertNull(policy.update(s(actions = false, last = inFlight), projecting = false))
        // po powrocie projekcji w tym samym pobycie — z wynikiem, jeśli nie był potwierdzeniem
        assertNotNull(policy.update(s(last = openDone(AckResult.BUSY, CommandOutcome.REJECTED)), projecting = true))
    }

    @Test
    fun staleDismissFromPreviousCycleIsIgnored() {
        val first = policy.update(s(), true)!!.cycle
        policy.update(s(presence = PresenceNotification.HIDDEN), true)
        val second = policy.update(s(), true)!!
        assertTrue(second.cycle != first)
        policy.onDismissedByDriver(first) // spóźnione „odrzucono” z poprzedniego pobytu
        assertNotNull(policy.update(s(), true))
    }

    @Test
    fun driverDismissHidesUntilNextCycle() {
        val cycle = policy.update(s(), true)!!.cycle
        policy.onDismissedByDriver(cycle)
        assertNull(policy.update(s(), true))
        policy.update(s(presence = PresenceNotification.HIDDEN), true)
        assertNotNull(policy.update(s(), true))
    }

    @Test
    fun quietHides() {
        policy.update(s(), true)
        assertNull(policy.update(s(presence = PresenceNotification.QUIET), true))
    }

    @Test
    fun alertsWhenFirstShownAndWhenButtonAppears() {
        assertTrue(policy.update(s(actions = false, connection = ConnectionState.CONNECTING), true)!!.alert)
        assertFalse(policy.update(s(actions = false, connection = ConnectionState.AUTHENTICATING), true)!!.alert)
        assertTrue(policy.update(s(), true)!!.alert) // pojawił się OTWÓRZ — kierowca musi to zauważyć
        assertFalse(policy.update(s(), true)!!.alert)
        // ponowne pojawienie się przycisku w tym samym pobycie (po chwilowej utracie łącza) już nie alarmuje
        policy.update(s(actions = false, connection = ConnectionState.DISCONNECTED), true)
        assertFalse(policy.update(s(), true)!!.alert)
    }

    @Test
    fun notificationOmitsProblems() {
        val n = policy.update(
            s(actions = false, connection = ConnectionState.DISCONNECTED).copy(
                systemProblem = com.agendek.bramav2.domain.SystemProblem.BLUETOOTH_OFF,
                handshakeProblem = UiMessage(MessageId.REJECT_WINDOW_CLOSED),
            ),
            true,
        )!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.NOT_CONNECTED))), n.lines)
    }

    @Test
    fun dismissReportsWhetherItClosed() {
        val first = policy.update(s(), true)!!.cycle
        assertFalse(policy.onDismissedByDriver(first + 1))
        assertTrue(policy.onDismissedByDriver(first))
    }

    @Test
    fun permissionProblemShowsConfigureOnPhone() {
        val n = policy.update(s(actions = false).copy(systemProblem = com.agendek.bramav2.domain.SystemProblem.BLUETOOTH_PERMISSION_MISSING), true)!!
        assertEquals(listOf(Texts.text(UiMessage(MessageId.CONFIGURE_ON_PHONE))), n.lines)
    }

    @Test
    fun cycleNumbersStartFromProcessSpecificValue() {
        // Numer z poprzedniego procesu (od 1) nie trafia w pierwszy pobyt nowego procesu.
        val p = CarNotificationPolicy(firstCycle = 41)
        val n = p.update(s(), true)!!
        assertEquals(42, n.cycle)
        assertFalse(p.onDismissedByDriver(1))
        assertNotNull(p.update(s(), true))
    }

    @Test
    fun configureStateHidesOpenButton() {
        // Uprawnienie cofnięte w trakcie sesji: łącze jeszcze READY, ale „Skonfiguruj…” — bez przycisku (jak ekran 5.2).
        val n = policy.update(s().copy(systemProblem = com.agendek.bramav2.domain.SystemProblem.BLUETOOTH_PERMISSION_MISSING), true)!!
        assertFalse(n.showOpen)
    }
}
