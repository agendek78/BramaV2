package com.agendek.bramav2.protocol

/**
 * Budżety czasu transportu (10.4) dla parametrów łącza zamrożonych na początku transferu/operacji.
 * Wszystkie wartości w milisekundach zegara monotonicznego. Efektywny interwał jest ograniczony
 * maksimum BLE (4000 ms × 500), dzięki czemu żaden budżet nie przekracza zakresu `Int`.
 */
class TimingBudget(attMtu: Int, connectionIntervalMs: Int, slaveLatency: Int) {
    init {
        require(attMtu >= MIN_ATT_MTU) { "ATT_MTU < $MIN_ATT_MTU" }
        require(connectionIntervalMs >= 1) { "Interwał < 1 ms" }
        require(slaveLatency in 0..MAX_SLAVE_LATENCY) { "slave_latency poza 0–$MAX_SLAVE_LATENCY" }
        require(connectionIntervalMs.toLong() * (slaveLatency + 1) <= MAX_EFFECTIVE_INTERVAL_MS) {
            "Efektywny interwał powyżej $MAX_EFFECTIVE_INTERVAL_MS ms"
        }
    }

    val gattValueBudget: Int = minOf(attMtu - 3, MAX_GATT_VALUE)
    val fragmentDataBudget: Int = gattValueBudget - Fragmenter.HEADER_SIZE
    val effectiveIntervalMs: Int = connectionIntervalMs * (slaveLatency + 1)
    val fragmentSlotMs: Int = maxOf(300, 4 * effectiveIntervalMs)
    val progressMs: Int = maxOf(5_000, 4 * effectiveIntervalMs)
    val maxFragments: Int = ceilDiv(Frame.MAX_SIZE, fragmentDataBudget)
    val responseMs: Int = 2 * frameMs(maxFragments) + 5_000
    val attemptMs: Int = 4 * responseMs

    /** Rejestracja i naprawa bond są przyjmowane tylko przy `T_attempt <= 180 s`. */
    val allowsRegistration: Boolean get() = attemptMs <= 180_000

    fun frameMs(fragmentCount: Int): Int {
        require(fragmentCount in 1..Fragmenter.MAX_FRAGMENTS)
        return 2_000 + fragmentCount * fragmentSlotMs
    }

    companion object {
        const val MAX_GATT_VALUE = 512
        const val MIN_ATT_MTU = 23
        const val MAX_SLAVE_LATENCY = 499

        /** Maksymalny interwał połączenia BLE (4 s) × (maksymalne slave_latency + 1). */
        const val MAX_EFFECTIVE_INTERVAL_MS = 4_000 * (MAX_SLAVE_LATENCY + 1)

        /** Telefon nie zna parametrów połączenia; używa przybliżenia z [PhoneIntervalEstimator]. */
        fun forPhone(attMtu: Int, estimator: PhoneIntervalEstimator) =
            TimingBudget(attMtu, estimator.effectiveIntervalMs, 0)
    }
}

/**
 * Przybliżenie interwału po stronie Androida (10.4): mediana czasu Write Request → onCharacteristicWrite
 * z ostatnich 8 fragmentów, nie mniej niż 100 ms; do pierwszego pomiaru 100 ms. Górnej granicy specyfikacja
 * nie przewiduje; jedynie skrajne próbki są przycinane do [TimingBudget.MAX_EFFECTIVE_INTERVAL_MS].
 * Nie jest bezpieczny wątkowo; używać z wątku/aktora kolejki GATT.
 */
class PhoneIntervalEstimator {
    private val samples = ArrayDeque<Long>()

    fun recordWriteDuration(ms: Long) {
        require(ms >= 0) { "Ujemny czas zapisu" }
        samples.addLast(ms)
        if (samples.size > WINDOW) samples.removeFirst()
    }

    val effectiveIntervalMs: Int
        get() {
            if (samples.isEmpty()) return MIN_MS
            val sorted = samples.sorted()
            val mid = sorted.size / 2
            // Średnia liczona bez przepełnienia przy skrajnych próbkach.
            val median = if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1] + (sorted[mid] - sorted[mid - 1]) / 2
            return median.coerceIn(MIN_MS.toLong(), TimingBudget.MAX_EFFECTIVE_INTERVAL_MS.toLong()).toInt()
        }

    private companion object {
        const val WINDOW = 8
        const val MIN_MS = 100
    }
}

internal fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
