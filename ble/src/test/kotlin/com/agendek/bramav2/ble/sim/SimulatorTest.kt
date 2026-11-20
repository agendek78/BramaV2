package com.agendek.bramav2.ble.sim

import com.agendek.bramav2.ble.core.GattProfile
import com.agendek.bramav2.ble.core.RadioEvent
import com.agendek.bramav2.protocol.Fragmenter
import com.agendek.bramav2.protocol.Frame
import com.agendek.bramav2.protocol.MessageType
import com.agendek.bramav2.protocol.Reassembler
import com.agendek.bramav2.protocol.ReassemblyResult
import com.agendek.bramav2.protocol.SessionHandshake
import com.agendek.bramav2.protocol.TimingBudget
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTest {

    private val clientId = ByteArray(16) { 0x44 }
    private val clientKey = ByteArray(32) { 0x55 }

    private fun TestScope.setup(): Triple<SimulatedGate, SimulatedRadio, MutableList<RadioEvent>> {
        val gate = SimulatedGate()
        gate.clients[clientId.toHex()] = clientKey
        val radio = SimulatedRadio(backgroundScope, gate)
        val events = mutableListOf<RadioEvent>()
        backgroundScope.launch { radio.events.collect { events += it } }
        runCurrent()
        return Triple(gate, radio, events)
    }

    private fun indications(events: List<RadioEvent>, mtu: Int): List<ByteArray> {
        val r = Reassembler { TimingBudget(mtu, 100, 0) }
        return events.filterIsInstance<RadioEvent.TxIndication>()
            .mapNotNull { (r.accept(it.value, 0) as? ReassemblyResult.Complete)?.frame }
    }

    @Test
    fun sessionHandshakeThroughRadio() = runTest {
        val (gate, radio, events) = setup()
        val h = radio.connect(SimulatedRadio.GATE_ADDRESS)
        radio.requestMtu(h, GattProfile.REQUESTED_MTU)
        advanceTimeBy(1_000)
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId)
        Fragmenter.fragment(hs.pendingRequest!!, 1, TimingBudget(247, 100, 0)).forEach { radio.writeRx(h, it) }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(events.any { it is RadioEvent.Connected })
        assertTrue(events.any { it is RadioEvent.RxWritten && it.ok })
        val challenge = Frame.decode(indications(events, 247).single())
        assertEquals(MessageType.SESSION_CHALLENGE, challenge.header.messageType)
    }

    @Test
    fun droppedIndicationIsNotDelivered() = runTest {
        val (gate, radio, events) = setup()
        val h = radio.connect(SimulatedRadio.GATE_ADDRESS)
        radio.dropIndications = 1
        val hs = SessionHandshake(clientKey, gate.deviceId, clientId)
        Fragmenter.fragment(hs.pendingRequest!!, 1, TimingBudget(23, 100, 0)).forEach { radio.writeRx(h, it) }
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(events.none { it is RadioEvent.TxIndication })
    }

    @Test
    fun cachedResponseForRetransmittedRequest() {
        val gate = SimulatedGate()
        gate.clients[clientId.toHex()] = clientKey
        val begin = SessionHandshake(clientKey, gate.deviceId, clientId).pendingRequest!!
        assertArrayEquals(gate.receive(begin), gate.receive(begin))
    }

    @Test
    fun disconnectedHandleEmitsNothingMore() = runTest {
        val (_, radio, events) = setup()
        val h = radio.connect(SimulatedRadio.GATE_ADDRESS)
        radio.disconnect(h)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(events.none { it is RadioEvent.OfHandle && it.handle == h })
    }
}
