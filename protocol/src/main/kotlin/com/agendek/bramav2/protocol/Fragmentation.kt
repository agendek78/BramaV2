package com.agendek.bramav2.protocol

import kotlin.random.Random

/** Podział kompletnej ramki na wartości GATT RX/TX (10.2). */
object Fragmenter {
    const val HEADER_SIZE = 3
    const val MAX_FRAGMENTS = 32

    fun fragment(frame: ByteArray, transferId: Int, budget: TimingBudget): List<ByteArray> {
        require(transferId in 1..255) { "transfer_id poza 1–255" }
        require(frame.isNotEmpty() && frame.size <= Frame.MAX_SIZE) { "Zła długość ramki" }
        val data = budget.fragmentDataBudget
        val count = ceilDiv(frame.size, data)
        require(count <= MAX_FRAGMENTS)
        return List(count) { i ->
            val from = i * data
            val to = minOf(from + data, frame.size)
            byteArrayOf(transferId.toByte(), i.toByte(), count.toByte()) + frame.copyOfRange(from, to)
        }
    }
}

/**
 * Kolejne `transfer_id`: 1–255, po 255 wraca do 1. Domyślnie start losowy, aby proces odtworzony na zachowanym
 * łączu (12.1) nie powtórzył id niedokończonego transferu poprzedniego procesu. Nie jest bezpieczny wątkowo.
 */
class TransferIdGenerator(start: Int = Random.nextInt(1, 256)) {
    private var next = start

    init {
        require(start in 1..255)
    }

    fun next(): Int = next.also { next = if (it == 255) 1 else it + 1 }
}

sealed interface ReassemblyResult {
    data object InProgress : ReassemblyResult
    class Complete(val frame: ByteArray) : ReassemblyResult
    data class Dropped(val reason: String) : ReassemblyResult
}

/**
 * Składanie jednej ramki na łączu w jednym kierunku (10.2) z limitami T_progress i T_frame (10.4).
 * Częściowa ramka nigdy nie trafia do logiki komend. [budgetProvider] jest wołany na początku transferu,
 * a jego wynik zamrażany do końca transferu. Nie jest bezpieczny wątkowo; jeden obiekt na łącze i kierunek,
 * używany z jednego wątku lub aktora.
 */
class Reassembler(private val budgetProvider: () -> TimingBudget) {
    private var transferId = 0
    private var count = 0
    private var nextIndex = 0
    private var budget: TimingBudget? = null
    private var startMs = 0L
    private var lastProgressMs = 0L
    private var buffer = ByteArray(0)

    val hasPartialFrame: Boolean get() = budget != null

    fun accept(fragment: ByteArray, nowMs: Long): ReassemblyResult {
        expire(nowMs)
        if (fragment.size <= Fragmenter.HEADER_SIZE) return drop("Pusty fragment")
        val id = fragment[0].toInt() and 0xff
        val index = fragment[1].toInt() and 0xff
        val total = fragment[2].toInt() and 0xff
        if (id == 0 || total !in 1..Fragmenter.MAX_FRAGMENTS || index >= total) return drop("Zły nagłówek fragmentu")

        if (index == 0) {
            if (hasPartialFrame && id == transferId) return drop("Powtórzony indeks 0 w trwającym transferze")
            reset()
            transferId = id
            count = total
            budget = budgetProvider()
            startMs = nowMs
            if (total > budget!!.maxFragments) return drop("fragment_count $total niemożliwy przy bieżącym MTU")
        } else if (!hasPartialFrame || id != transferId || total != count || index != nextIndex) {
            return drop("Błąd kolejności fragmentów")
        }

        val dataLen = fragment.size - Fragmenter.HEADER_SIZE
        val fullBudget = budget!!.fragmentDataBudget
        val isLast = index == count - 1
        if (dataLen > fullBudget || (!isLast && dataLen != fullBudget)) return drop("Zła długość fragmentu")
        if (buffer.size + dataLen > Frame.MAX_SIZE) return drop("Ramka dłuższa niż ${Frame.MAX_SIZE} B")

        buffer += fragment.copyOfRange(Fragmenter.HEADER_SIZE, fragment.size)
        nextIndex = index + 1
        lastProgressMs = nowMs
        if (!isLast) return ReassemblyResult.InProgress
        return ReassemblyResult.Complete(buffer).also { reset() }
    }

    /** Usuwa niedokończony bufor po T_progress lub T_frame; zwraca `true`, jeśli bufor został usunięty. */
    fun expire(nowMs: Long): Boolean {
        val b = budget ?: return false
        val expired = nowMs - lastProgressMs > b.progressMs || nowMs - startMs > b.frameMs(count)
        if (expired) reset()
        return expired
    }

    private fun drop(reason: String): ReassemblyResult {
        reset()
        return ReassemblyResult.Dropped(reason)
    }

    private fun reset() {
        transferId = 0
        count = 0
        nextIndex = 0
        budget = null
        buffer = ByteArray(0)
    }
}
