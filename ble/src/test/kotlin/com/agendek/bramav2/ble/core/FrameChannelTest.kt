package com.agendek.bramav2.ble.core

import com.agendek.bramav2.protocol.Fragmenter
import com.agendek.bramav2.protocol.TimingBudget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameChannelTest {

    private val radio = RecordingRadio()
    private val h = RadioHandle(1)
    private var now = 0L
    private val queue = GattQueue(radio, h) { now }
    private val channel = FrameChannel(queue, clock = { now })

    private fun frame(len: Int) = ByteArray(len) { (it * 3).toByte() }

    /** Kończy bieżący zapis w kolejce i przekazuje wynik do kanału, jak robi to aktor. */
    private fun completeWrite(ok: Boolean = true, afterMs: Long = 100): SendResult? {
        now += afterMs
        val done = queue.onEvent(RadioEvent.RxWritten(h, ok))!!
        return channel.onWriteCompleted(ok, done.startedAt)
    }

    @Test
    fun frameOf39BytesAtMtu23Is3Writes() {
        channel.sendFrame(frame(39))
        repeat(2) { completeWrite() }
        completeWrite()
        assertEquals(listOf(20, 20, 8), radio.written.map { it.size })
        assertEquals(1, radio.written.map { it[0] }.toSet().size) // jeden transfer_id
    }

    @Test
    fun sentAfterLastWriteCompletes() {
        channel.sendFrame(frame(39))
        assertNull(completeWrite())
        assertNull(completeWrite())
        assertEquals(SendResult.Sent, completeWrite())
    }

    @Test
    fun writeFailureReportsFailed() {
        channel.sendFrame(frame(39))
        assertEquals(SendResult.Failed, completeWrite(ok = false))
    }

    @Test
    fun reassemblesIndications() {
        val data = frame(53)
        val fragments = Fragmenter.fragment(data, 7, TimingBudget(23, 100, 0))
        fragments.dropLast(1).forEach { assertNull(channel.onIndication(it)) }
        assertArrayEquals(data, channel.onIndication(fragments.last()))
    }

    @Test
    fun indicationDuringPendingWriteIsNotLost() {
        channel.mtu = 247
        channel.sendFrame(frame(39)) // zapis w toku, RxWritten jeszcze nie przyszło
        val response = frame(63)
        val single = Fragmenter.fragment(response, 9, TimingBudget(247, 100, 0)).single()
        assertArrayEquals(response, channel.onIndication(single))
        assertEquals(SendResult.Sent, completeWrite())
    }

    @Test
    fun responseBudgetFollowsMtuAndEstimator() {
        channel.mtu = 247
        channel.sendFrame(frame(39))
        completeWrite(afterMs = 100)
        assertEquals(11_400L, channel.responseBudgetMs())
        channel.mtu = 23
        assertEquals(33_800L, channel.responseBudgetMs())
    }

    @Test
    fun slowWritesStretchBudget() {
        channel.mtu = 247
        repeat(8) {
            channel.sendFrame(frame(39))
            completeWrite(afterMs = 400)
        }
        // interval 400 ms → slot 1600, T_frame(3) = 6800, T_response = 2·6800 + 5000
        assertEquals(18_600L, channel.responseBudgetMs())
    }

    @Test
    fun retransmissionUsesNewTransferIdSameBytes() {
        channel.mtu = 247
        val data = frame(39)
        channel.sendFrame(data)
        completeWrite()
        channel.sendFrame(data)
        completeWrite()
        val (a, b) = radio.written
        assertNotEquals(a[0], b[0])
        assertArrayEquals(a.copyOfRange(Fragmenter.HEADER_SIZE, a.size), b.copyOfRange(Fragmenter.HEADER_SIZE, b.size))
    }

    @Test
    fun partialIndicationExpires() {
        val fragments = Fragmenter.fragment(frame(53), 7, TimingBudget(23, 100, 0))
        channel.onIndication(fragments[0])
        now += 10_000 // Reassembler wygasza niedokończoną ramkę przy następnym fragmencie
        assertNull(channel.onIndication(fragments[1])) // bufor usunięty — kontynuacja odrzucona
    }

    @Test
    fun timeQueuedBehindOtherOperationIsNotWriteDuration() {
        channel.mtu = 247
        queue.enqueue(GattOp.RequestMtu(247)) // inna operacja w toku
        channel.sendFrame(frame(39))
        now += 3_000
        queue.onEvent(RadioEvent.MtuChanged(h, 247)) // zapis startuje dopiero teraz
        completeWrite(afterMs = 100)
        assertEquals(11_400L, channel.responseBudgetMs()) // interwał 100 ms, nie 3,1 s
    }
}
