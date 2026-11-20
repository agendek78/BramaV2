package com.agendek.bramav2.ble.core

import org.junit.Test
import org.junit.Assert.assertEquals

class GateAdvertisementTest {
    @Test
    fun noFieldIsUnknown() = assertEquals(PinAvailability.Unknown, GateAdvertisement.pinAvailability(null))

    @Test
    fun zeroAttemptsIsUnavailable() = assertEquals(PinAvailability.Unavailable, GateAdvertisement.pinAvailability(byteArrayOf(0)))

    @Test
    fun attemptsLeftAreReported() {
        for (n in 1..5) assertEquals(PinAvailability.Available(n), GateAdvertisement.pinAvailability(byteArrayOf(n.toByte())))
    }

    @Test
    fun malformedFieldIsUnknownNotBlocking() {
        assertEquals(PinAvailability.Unknown, GateAdvertisement.pinAvailability(byteArrayOf()))
        assertEquals(PinAvailability.Unknown, GateAdvertisement.pinAvailability(byteArrayOf(6)))
        assertEquals(PinAvailability.Unknown, GateAdvertisement.pinAvailability(byteArrayOf(-1)))
    }

    @Test
    fun extraBytesAfterCounterAreIgnored() {
        assertEquals(PinAvailability.Available(3), GateAdvertisement.pinAvailability(byteArrayOf(3, 9)))
        assertEquals(PinAvailability.Unavailable, GateAdvertisement.pinAvailability(byteArrayOf(0, 1)))
    }

    @Test
    fun aggregateIgnoresGatesWithoutField() {
        assertEquals(PinAvailability.Unknown, GateAdvertisement.aggregate(emptyList()))
        assertEquals(PinAvailability.Unknown, GateAdvertisement.aggregate(listOf(PinAvailability.Unknown)))
    }

    @Test
    fun aggregateBlocksOnlyWhenEveryGateWithFieldIsUnavailable() {
        assertEquals(PinAvailability.Unavailable, GateAdvertisement.aggregate(listOf(PinAvailability.Unavailable, PinAvailability.Unknown)))
        assertEquals(
            PinAvailability.Available(5),
            GateAdvertisement.aggregate(listOf(PinAvailability.Unavailable, PinAvailability.Available(5))),
        )
    }

    @Test
    fun aggregateTakesBestAvailable() {
        assertEquals(
            PinAvailability.Available(4),
            GateAdvertisement.aggregate(listOf(PinAvailability.Available(2), PinAvailability.Available(4))),
        )
    }
}
