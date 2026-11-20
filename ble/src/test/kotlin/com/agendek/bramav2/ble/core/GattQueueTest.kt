package com.agendek.bramav2.ble.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattQueueTest {

    private val radio = RecordingRadio()
    private val h = RadioHandle(1)
    private var now = 0L
    private val queue = GattQueue(radio, h) { now }

    @Test
    fun runsOneOperationAtATime() {
        queue.enqueue(GattOp.Discover)
        queue.enqueue(GattOp.ReadInfo)
        assertEquals(listOf("discover"), radio.calls)
        val done = queue.onEvent(RadioEvent.ServicesDiscovered(h, ok = true))
        assertEquals(GattOp.Discover, done!!.op)
        assertEquals(GattResult.Ok(null), done.result)
        assertEquals(listOf("discover", "readInfo"), radio.calls)
        assertEquals(GattOp.ReadInfo, queue.current)
    }

    @Test
    fun failedCallbackCompletesAsFailed() {
        queue.enqueue(GattOp.EnableIndications)
        assertEquals(GattResult.Failed, queue.onEvent(RadioEvent.IndicationsEnabled(h, ok = false))!!.result)
    }

    @Test
    fun timeoutCompletesCurrentButNextWaitsForLateCallbackOrGrace() {
        queue.enqueue(GattOp.Discover)
        queue.enqueue(GattOp.ReadInfo)
        assertEquals(10_000L, queue.currentTimeoutMs)
        val done = queue.onTimeout()!!
        assertEquals(GattOp.Discover, done.op)
        assertEquals(GattResult.TimedOut, done.result)
        assertEquals(listOf("discover"), radio.calls) // następna operacja czeka
        assertEquals(10_000L, queue.currentTimeoutMs) // karencja na spóźniony callback
        assertNull(queue.onTimeout()) // karencja minęła → start następnej
        assertEquals(listOf("discover", "readInfo"), radio.calls)
        assertEquals(5_000L, queue.currentTimeoutMs)
    }

    @Test
    fun lateCallbackOfTimedOutOpDoesNotCompleteNextOfSameType() {
        queue.enqueue(GattOp.WriteRx(byteArrayOf(1)))
        queue.enqueue(GattOp.WriteRx(byteArrayOf(2)))
        queue.onTimeout()
        assertNull(queue.onEvent(RadioEvent.RxWritten(h, ok = true))) // spóźniony callback pierwszego zapisu
        assertEquals(2, radio.written.size) // dopiero teraz drugi zapis
        assertEquals(GattResult.Ok(null), queue.onEvent(RadioEvent.RxWritten(h, ok = true))!!.result)
    }

    @Test
    fun clearDuringOperationWaitsForItsCallback() {
        queue.enqueue(GattOp.WriteRx(byteArrayOf(1)))
        queue.clear()
        queue.enqueue(GattOp.WriteRx(byteArrayOf(2)))
        assertEquals(1, radio.written.size)
        assertNull(queue.onEvent(RadioEvent.RxWritten(h, ok = true)))
        assertEquals(2, radio.written.size)
    }

    @Test
    fun generationChangesOnEveryStartAndCompletionCarriesStartTime() {
        queue.enqueue(GattOp.Discover)
        val g1 = queue.generation
        now = 300
        queue.enqueue(GattOp.ReadInfo)
        now = 1_000
        val done = queue.onEvent(RadioEvent.ServicesDiscovered(h, ok = true))!!
        assertEquals(0L, done.startedAt)
        assertTrue(queue.generation != g1)
        now = 1_500
        assertEquals(1_000L, queue.onEvent(RadioEvent.InfoRead(h, byteArrayOf(1)))!!.startedAt)
    }

    @Test
    fun eventOfOtherHandleIsIgnored() {
        queue.enqueue(GattOp.Discover)
        assertNull(queue.onEvent(RadioEvent.ServicesDiscovered(RadioHandle(2), ok = true)))
        assertEquals(GattOp.Discover, queue.current)
    }

    @Test
    fun txIndicationIsNotACompletion() {
        queue.enqueue(GattOp.WriteRx(byteArrayOf(1)))
        assertNull(queue.onEvent(RadioEvent.TxIndication(h, byteArrayOf(9))))
        assertEquals(GattOp.WriteRx::class, queue.current!!::class)
    }

    @Test
    fun mtuChangedCarriesValue() {
        queue.enqueue(GattOp.RequestMtu(247))
        assertEquals(listOf("mtu:247"), radio.calls)
        assertEquals(GattResult.Ok(247), queue.onEvent(RadioEvent.MtuChanged(h, 247))!!.result)
    }

    @Test
    fun infoReadCarriesBytes() {
        queue.enqueue(GattOp.ReadInfo)
        val result = queue.onEvent(RadioEvent.InfoRead(h, byteArrayOf(2, 3)))!!.result as GattResult.Ok
        assertArrayEquals(byteArrayOf(2, 3), result.value as ByteArray)
    }

    @Test
    fun missingInfoIsFailure() {
        queue.enqueue(GattOp.ReadInfo)
        assertEquals(GattResult.Failed, queue.onEvent(RadioEvent.InfoRead(h, null))!!.result)
    }

    @Test
    fun writeCompletesAndBondCompletes() {
        queue.enqueue(GattOp.WriteRx(byteArrayOf(7)))
        queue.enqueue(GattOp.CreateBond)
        assertArrayEquals(byteArrayOf(7), radio.written.single())
        assertEquals(GattResult.Ok(null), queue.onEvent(RadioEvent.RxWritten(h, ok = true))!!.result)
        assertEquals(30_000L, queue.currentTimeoutMs)
        assertEquals(GattResult.Ok(null), queue.onEvent(RadioEvent.BondResult(h, ok = true))!!.result)
        assertNull(queue.current)
        assertNull(queue.currentTimeoutMs)
    }
}
