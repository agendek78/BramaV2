package com.agendek.bramav2.ble.core

import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.protocol.AckResult
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.Fragmenter
import com.agendek.bramav2.protocol.SessionRequest
import com.agendek.bramav2.protocol.TimingBudget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleGateLinkSessionTest {

    private fun LinkHarness.ready(): Long = connect().also { settle() }

    private inline fun <reified T : LinkEvent> LinkHarness.last(id: Long): T = eventsOf(id).filterIsInstance<T>().last()

    @Test
    fun openAckIsReportedWithSnapshot() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.link.send(SessionRequest.Open(true))
        h.settle()
        val result = h.last<LinkEvent.CommandResult>(id)
        assertEquals(SessionRequest.Open(true), result.request)
        assertEquals(AckResult.OUTPUT_TRIGGERED, result.ack.result)
        assertEquals(true, result.snapshotApplied)
        assertEquals(listOf("OPEN"), h.gate.impulses)
    }

    @Test
    fun lostAckIsRecoveredByRetransmissionWithoutSecondImpulse() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.dropIndications = 1
        h.link.send(SessionRequest.Close)
        h.settle(11_000)
        assertTrue(h.eventsOf(id).none { it is LinkEvent.CommandResult })
        h.settle(5_000) // po T_response = 11 400 ms retransmisja identycznych bajtów → ACK z cache
        assertEquals(SessionRequest.Close, h.last<LinkEvent.CommandResult>(id).request)
        assertEquals(listOf("CLOSE"), h.gate.impulses)
    }

    @Test
    fun twoLostAcksStillResolve() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.dropIndications = 2
        h.link.send(SessionRequest.Close)
        h.settle(40_000)
        assertEquals(SessionRequest.Close, h.last<LinkEvent.CommandResult>(id).request)
        assertTrue(h.eventsOf(id).none { it is LinkEvent.CommandUnknown })
        assertEquals(1, h.gate.impulses.size)
    }

    @Test
    fun thirdTimeoutIsUnknownAndRekeysOnSameLink() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.dropIndications = 3
        h.link.send(SessionRequest.Close)
        h.settle(60_000)
        val names = h.namesOf(id)
        val unknown = names.indexOf("CommandUnknown")
        assertTrue(unknown > 0)
        assertEquals(listOf("CommandUnknown", "Rekeying", "Ready"), names.subList(unknown, names.size))
        assertEquals(LinkEvent.CommandUnknown(SessionRequest.Close), h.eventsOf(id)[unknown])
        assertTrue("disconnect" !in h.radio.calls)
    }

    @Test
    fun ruleStatusBeforeAckDoesNotResolveCommand() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.link.send(SessionRequest.Open(true))
        h.radio.pushRuleStatus(AutoClosePhase.MONITORING) // status przed ACK
        h.settle()
        val names = h.namesOf(id)
        assertTrue(names.indexOf("RuleStatus") < names.indexOf("CommandResult"))
        assertEquals(SessionRequest.Open(true), h.last<LinkEvent.CommandResult>(id).request)
    }

    @Test
    fun pingYieldsPong() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.link.send(SessionRequest.Ping)
        h.settle()
        assertEquals("Pong", h.namesOf(id).last())
    }

    @Test
    fun sendOutsideReadyIsUnknown() = runTest {
        val h = LinkHarness(this)
        val id = h.connect() // jeszcze bez READY
        h.link.send(SessionRequest.Close)
        h.settle()
        assertTrue(LinkEvent.CommandUnknown(SessionRequest.Close) in h.eventsOf(id))
        assertTrue(h.gate.impulses.isEmpty())
    }

    @Test
    fun oldSessionFrameAfterRekeyIsIgnored() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        val stale = h.gate.pushRuleStatus(AutoClosePhase.MONITORING) // ramka starej sesji, niedostarczona
        h.radio.dropIndications = 3
        h.link.send(SessionRequest.Ping)
        h.settle(60_000) // rekey → nowa sesja
        val before = h.eventsOf(id).size
        Fragmenter.fragment(stale, 99, TimingBudget(247, 100, 0)).forEach {
            h.radio.inject(RadioEvent.TxIndication(h.radio.currentHandle!!, it))
        }
        h.settle()
        assertEquals(before, h.eventsOf(id).size)
        h.link.send(SessionRequest.Close)
        h.settle()
        assertEquals(SessionRequest.Close, h.last<LinkEvent.CommandResult>(id).request)
    }

    @Test
    fun radioLossDuringCommandIsUnknownThenDisconnected() = runTest {
        val h = LinkHarness(this)
        val id = h.ready()
        h.radio.dropIndications = 1
        h.link.send(SessionRequest.Close)
        h.settle(1_000)
        h.radio.dropLink()
        h.settle()
        assertEquals(listOf("CommandUnknown", "Disconnected"), h.namesOf(id).takeLast(2))
    }
}
