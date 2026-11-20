package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentationTest {

    private val budget23 = TimingBudget(attMtu = 23, connectionIntervalMs = 100, slaveLatency = 0)

    private fun frame(len: Int) = ByteArray(len) { (it * 7).toByte() }

    @Test
    fun fragmentsFullBudgetExceptLast() {
        val frags = Fragmenter.fragment(frame(39), transferId = 5, budget23)
        assertEquals(3, frags.size)
        assertEquals(listOf(20, 20, 8), frags.map { it.size })
        frags.forEachIndexed { i, f ->
            assertEquals(5, f[0].toInt())
            assertEquals(i, f[1].toInt())
            assertEquals(3, f[2].toInt())
        }
    }

    @Test
    fun specMessageSizesAtMtu23() {
        assertEquals(3, Fragmenter.fragment(frame(39), 1, budget23).size)  // OPEN
        assertEquals(3, Fragmenter.fragment(frame(38), 1, budget23).size)  // CLOSE/PING
        assertEquals(4, Fragmenter.fragment(frame(53), 1, budget23).size)  // READY/PONG/STATUS
        assertEquals(4, Fragmenter.fragment(frame(63), 1, budget23).size)  // ACK
        assertEquals(31, Fragmenter.fragment(frame(512), 1, budget23).size)
    }

    @Test
    fun maxFrameAtMtu517HasFirstValueOf512() {
        val frags = Fragmenter.fragment(frame(512), 1, TimingBudget(517, 30, 0))
        assertEquals(listOf(512, 6), frags.map { it.size })
    }

    @Test
    fun transferIdWrapsFrom255To1() {
        val gen = TransferIdGenerator(start = 254)
        assertEquals(listOf(254, 255, 1, 2), List(4) { gen.next() })
    }

    @Test
    fun defaultGeneratorStartsAtRandomIdSoRestartedAppDoesNotReusePreviousId() {
        val firsts = List(64) { TransferIdGenerator().next() }.toSet()
        assertTrue(firsts.size > 1)
        assertTrue(firsts.all { it in 1..255 })
    }

    @Test
    fun reassemblesInOrder() {
        val r = Reassembler { budget23 }
        val data = frame(63)
        val results = Fragmenter.fragment(data, 9, budget23).mapIndexed { i, f -> r.accept(f, nowMs = i * 100L) }
        assertTrue(results.dropLast(1).all { it == ReassemblyResult.InProgress })
        assertArrayEquals(data, (results.last() as ReassemblyResult.Complete).frame)
    }

    @Test
    fun outOfOrderFragmentDropsBuffer() {
        val r = Reassembler { budget23 }
        val f = Fragmenter.fragment(frame(53), 9, budget23)
        r.accept(f[0], 0)
        assertTrue(r.accept(f[2], 1) is ReassemblyResult.Dropped)
        assertTrue(r.accept(f[3], 2) is ReassemblyResult.Dropped) // brak bufora
    }

    @Test
    fun indexZeroWithNewTransferIdRestartsFrame() {
        val r = Reassembler { budget23 }
        val old = Fragmenter.fragment(frame(53), 9, budget23)
        val data = frame(39)
        val fresh = Fragmenter.fragment(data, 10, budget23)
        r.accept(old[0], 0)
        r.accept(old[1], 1)
        assertEquals(ReassemblyResult.InProgress, r.accept(fresh[0], 2))
        r.accept(fresh[1], 3)
        assertArrayEquals(data, (r.accept(fresh[2], 4) as ReassemblyResult.Complete).frame)
    }

    @Test
    fun indexZeroWithSameTransferIdIsOrderErrorAndDoesNotStart() {
        val r = Reassembler { budget23 }
        val f = Fragmenter.fragment(frame(53), 9, budget23)
        r.accept(f[0], 0)
        assertTrue(r.accept(f[0], 1) is ReassemblyResult.Dropped)
        assertTrue(r.accept(f[1], 2) is ReassemblyResult.Dropped)
    }

    @Test
    fun rejectsMalformedFragments() {
        val r = Reassembler { budget23 }
        assertTrue(r.accept(byteArrayOf(1, 0, 1), 0) is ReassemblyResult.Dropped)        // pusty
        assertTrue(r.accept(byteArrayOf(0, 0, 1, 5), 0) is ReassemblyResult.Dropped)     // transfer_id 0
        assertTrue(r.accept(byteArrayOf(1, 0, 33, 5), 0) is ReassemblyResult.Dropped)    // count > 32
        assertTrue(r.accept(byteArrayOf(1, 0, 2, 5), 0) is ReassemblyResult.Dropped)     // niepełny nie-ostatni
        assertTrue(r.accept(byteArrayOf(1, 0, 0, 5), 0) is ReassemblyResult.Dropped)     // count 0
    }

    @Test
    fun rejectsFragmentCountImpossibleForNegotiatedMtu() {
        val b247 = TimingBudget(247, 30, 0) // 512 B mieści się w 3 fragmentach
        val r = Reassembler { b247 }
        val first = byteArrayOf(1, 0, 4) + ByteArray(b247.fragmentDataBudget)
        assertTrue(r.accept(first, 0) is ReassemblyResult.Dropped)
        assertEquals(false, r.hasPartialFrame)
    }

    @Test
    fun rejectsFrameOver512() {
        val big = TimingBudget(517, 30, 0)
        val r = Reassembler { big }
        val first = byteArrayOf(1, 0, 2) + ByteArray(509)
        assertEquals(ReassemblyResult.InProgress, r.accept(first, 0))
        assertTrue(r.accept(byteArrayOf(1, 1, 2) + ByteArray(4), 1) is ReassemblyResult.Dropped)
    }

    @Test
    fun progressTimeoutDropsBuffer() {
        val r = Reassembler { budget23 }
        val f = Fragmenter.fragment(frame(512), 9, budget23) // T_frame(31) = 14,4 s, więc decyduje T_progress = 5 s
        r.accept(f[0], 0)
        r.accept(f[1], 4_000)
        assertTrue(r.accept(f[2], 9_001) is ReassemblyResult.Dropped)
    }

    @Test
    fun frameTimeoutIsNotExtendedByProgress() {
        val r = Reassembler { budget23 }
        val f = Fragmenter.fragment(frame(512), 9, budget23) // T_frame(31) = 14 400 ms
        var t = 0L
        val results = f.map { frag -> r.accept(frag, t).also { t += 490 } }
        // ostatni fragment przy t = 30 × 490 = 14 700 ms > 14 400 ms
        assertTrue(results.last() is ReassemblyResult.Dropped)
    }

    @Test
    fun shortFrameExpiresByFrameLimit() {
        val r = Reassembler { budget23 }
        r.accept(Fragmenter.fragment(frame(53), 9, budget23)[0], 0) // T_frame(4) = 3,6 s
        assertEquals(false, r.expire(3_600))
        assertEquals(true, r.expire(3_601))
    }

    @Test
    fun pollReportsExpiry() {
        val r = Reassembler { budget23 }
        r.accept(Fragmenter.fragment(frame(512), 9, budget23)[0], 0)
        assertEquals(false, r.expire(4_999))
        assertEquals(true, r.expire(5_001))
        assertEquals(false, r.hasPartialFrame)
    }
}
