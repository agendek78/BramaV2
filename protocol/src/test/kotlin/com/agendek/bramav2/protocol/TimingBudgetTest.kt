package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingBudgetTest {

    @Test
    fun specExampleMtu23Interval100() {
        val b = TimingBudget(attMtu = 23, connectionIntervalMs = 100, slaveLatency = 0)
        assertEquals(17, b.fragmentDataBudget)
        assertEquals(31, b.maxFragments)
        assertEquals(400, b.fragmentSlotMs)
        assertEquals(5_000, b.progressMs)
        assertEquals(14_400, b.frameMs(31))
        assertEquals(33_800, b.responseMs)
        assertEquals(135_200, b.attemptMs)
    }

    @Test
    fun slaveLatencyExtendsEffectiveInterval() {
        val b = TimingBudget(attMtu = 247, connectionIntervalMs = 1000, slaveLatency = 1)
        assertEquals(8_000, b.fragmentSlotMs)
        assertEquals(8_000, b.progressMs)
    }

    @Test
    fun dataBudgetFollowsNegotiatedMtuCappedAt512() {
        assertEquals(241, TimingBudget(247, 30, 0).fragmentDataBudget)
        assertEquals(509, TimingBudget(517, 30, 0).fragmentDataBudget)
        assertEquals(509, TimingBudget(600, 30, 0).fragmentDataBudget)
    }

    @Test
    fun phoneEstimatorDefaultsTo100AndUsesMedianOfLast8() {
        val e = PhoneIntervalEstimator()
        assertEquals(100, e.effectiveIntervalMs)
        listOf(10L, 20L, 30L).forEach(e::recordWriteDuration)
        assertEquals(100, e.effectiveIntervalMs) // nigdy poniżej 100 ms
        repeat(8) { e.recordWriteDuration(400) }
        assertEquals(400, e.effectiveIntervalMs)
        repeat(5) { e.recordWriteDuration(150) } // ostatnie 8: 3×400, 5×150 → mediana 150
        assertEquals(150, e.effectiveIntervalMs)
    }

    @Test
    fun registrationAllowedOnlyWhenAttemptAtMost180s() {
        assertEquals(true, TimingBudget(23, 100, 0).allowsRegistration)
        assertEquals(false, TimingBudget(23, 200, 0).allowsRegistration)
    }

    @Test
    fun tinyMtuIsRejectedAsIllegalArgument() {
        for (mtu in listOf(0, 5, 6, 22)) {
            assertThrows(IllegalArgumentException::class.java) { TimingBudget(mtu, 100, 0) }
        }
    }

    @Test
    fun parametersOutsideBleRangeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TimingBudget(23, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { TimingBudget(23, 100, -1) }
        assertThrows(IllegalArgumentException::class.java) { TimingBudget(23, 100, 500) }
        // efektywny interwał powyżej maksimum BLE: 4000 ms × (499 + 1) = 2 000 000 ms
        assertThrows(IllegalArgumentException::class.java) { TimingBudget(23, 4_001, 499) }
        assertThrows(IllegalArgumentException::class.java) { TimingBudget(23, 2_000_001, 0) }
    }

    @Test
    fun phoneEstimateAboveSingleBleIntervalIsAccepted() {
        // telefon podaje przybliżenie z czasu zapisu (10.4), które może przekraczać 4 s
        assertEquals(24_000, TimingBudget(23, 6_000, 0).fragmentSlotMs)
    }

    @Test
    fun worstCaseBleParametersDoNotOverflow() {
        for (b in listOf(TimingBudget(23, 4_000, 499), TimingBudget(23, 2_000_000, 0))) {
            assertTrue(b.frameMs(Fragmenter.MAX_FRAGMENTS) > 0)
            assertTrue(b.responseMs > 0)
            assertTrue(b.attemptMs > b.responseMs)
            assertEquals(false, b.allowsRegistration)
        }
    }

    @Test
    fun estimatorHasNoUpperBoundWithinBleRange() {
        val e = PhoneIntervalEstimator()
        repeat(8) { e.recordWriteDuration(6_000) }
        assertEquals(6_000, e.effectiveIntervalMs) // 10.4: max(100, obserwowany)
    }

    @Test
    fun estimatorClampsOnlyBeyondMaximumEffectiveBleInterval() {
        val e = PhoneIntervalEstimator()
        repeat(8) { e.recordWriteDuration(Long.MAX_VALUE) }
        assertEquals(2_000_000, e.effectiveIntervalMs)
        TimingBudget.forPhone(23, e) // nie rzuca
    }

    @Test
    fun estimatorRejectsNegativeDurations() {
        assertThrows(IllegalArgumentException::class.java) { PhoneIntervalEstimator().recordWriteDuration(-1) }
    }
}
