package com.agendek.bramav2.ble.core

import com.agendek.bramav2.protocol.Fragmenter
import com.agendek.bramav2.protocol.PhoneIntervalEstimator
import com.agendek.bramav2.protocol.Reassembler
import com.agendek.bramav2.protocol.TimingBudget
import com.agendek.bramav2.protocol.TransferIdGenerator

sealed interface SendResult {
    /** Ostatni fragment potwierdzony przez callback zapisu — od tej chwili liczy się `T_response` (spec 10.4). */
    data object Sent : SendResult
    data object Failed : SendResult
}

/**
 * Ramki protokołu na RX/TX (spec 10.2, 10.4): fragmentacja zapisów przez [GattQueue], składanie wskazań,
 * pomiar czasu Write Request → callback do [PhoneIntervalEstimator] i budżety [TimingBudget.forPhone].
 * Retransmisja to ponowne [sendFrame] identycznych bajtów — dostaje nowy `transfer_id`.
 * Nie jest bezpieczny wątkowo — używać z aktora.
 */
class FrameChannel(
    private val queue: GattQueue,
    private val clock: () -> Long,
    private val transferIds: TransferIdGenerator = TransferIdGenerator(),
) {
    /** Wynegocjowane ATT_MTU (spec 8.1: żądane 247, praca także przy 23). */
    var mtu: Int = 23

    private val estimator = PhoneIntervalEstimator()
    private val reassembler = Reassembler { budget() }
    private var pendingWrites = 0

    fun budget(): TimingBudget = TimingBudget.forPhone(mtu, estimator)

    fun sendFrame(frame: ByteArray) {
        val fragments = Fragmenter.fragment(frame, transferIds.next(), budget())
        pendingWrites += fragments.size
        fragments.forEach { queue.enqueue(GattOp.WriteRx(it)) }
    }

    /**
     * Wywoływane po zakończeniu operacji `WriteRx` w kolejce; [startedAt] to faktyczny start zapisu
     * ([GattCompletion.startedAt]) — czas czekania za innymi operacjami nie jest czasem zapisu.
     */
    fun onWriteCompleted(ok: Boolean, startedAt: Long): SendResult? {
        if (pendingWrites == 0) return null
        if (!ok) {
            pendingWrites = 0
            return SendResult.Failed
        }
        estimator.recordWriteDuration(maxOf(0L, clock() - startedAt))
        pendingWrites--
        return if (pendingWrites == 0) SendResult.Sent else null
    }

    /** Kompletna ramka z wskazań TX albo `null` (także dla fragmentów odrzuconych przez [Reassembler]). */
    fun onIndication(value: ByteArray): ByteArray? =
        (reassembler.accept(value, clock()) as? com.agendek.bramav2.protocol.ReassemblyResult.Complete)?.frame

    /** Budżet `T_response` dla bieżących parametrów; wołający zamraża go per operacja. */
    fun responseBudgetMs(): Long = budget().responseMs.toLong()

    /** Budżet `T_attempt` próby handshake (spec 10.4). */
    fun attemptBudgetMs(): Long = budget().attemptMs.toLong()

    /** Porzuca stan wysyłania (rozłączenie). */
    fun reset() {
        pendingWrites = 0
    }
}
