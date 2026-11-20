package com.agendek.bramav2.ble.core

import com.agendek.bramav2.ble.sim.CountingNonces
import com.agendek.bramav2.ble.sim.SimulatedGate
import com.agendek.bramav2.ble.sim.SimulatedRadio
import com.agendek.bramav2.domain.LinkEvent
import com.agendek.bramav2.domain.LinkMessage
import com.agendek.bramav2.protocol.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/** `BleGateLink` nad symulowanym radiem i sterownikiem; klient zarejestrowany w sterowniku. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkHarness(val test: TestScope) {
    val gate = SimulatedGate()
    val radio = SimulatedRadio(test.backgroundScope, gate)
    val link = BleGateLink(test.backgroundScope, radio, { test.testScheduler.currentTime }, CountingNonces(1))
    val messages = mutableListOf<LinkMessage>()

    /** Wirtualny czas emisji kolejnych [messages]. */
    val times = mutableListOf<Long>()
    val clientId = ByteArray(16) { 0x44 }
    val clientKey = ByteArray(32) { 0x55 }

    init {
        gate.clients[clientId.toHex()] = clientKey.copyOf()
        test.backgroundScope.launch {
            link.events.collect {
                messages += it
                times += test.testScheduler.currentTime
            }
        }
        link.start()
        test.runCurrent()
    }

    fun connect(): Long = link.connect(gate.deviceId, clientId, clientKey)

    fun settle(ms: Long = 20_000) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }

    fun eventsOf(id: Long): List<LinkEvent> = messages.filter { it.connectionId == id }.map { it.event }

    fun namesOf(id: Long): List<String> = eventsOf(id).map { it::class.simpleName!! }

    /** Odstępy między zdarzeniem pasującym do [after] a następnym `Rekeying` (ponowienie na zachowanym łączu). */
    fun retryDelays(after: (LinkEvent) -> Boolean): List<Long> {
        val result = mutableListOf<Long>()
        messages.forEachIndexed { i, m ->
            if (!after(m.event)) return@forEachIndexed
            val next = (i + 1 until messages.size).firstOrNull { messages[it].event == LinkEvent.Rekeying } ?: return@forEachIndexed
            result += times[next] - times[i]
        }
        return result
    }
}
