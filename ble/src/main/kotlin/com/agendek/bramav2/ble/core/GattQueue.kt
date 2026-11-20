package com.agendek.bramav2.ble.core

/** Operacje GATT wykonywane przez [GattQueue] (spec 8.1: po kolei, czekając na callback poprzedniej). */
sealed interface GattOp {
    data object Discover : GattOp
    data object ReadInfo : GattOp
    data object ReadFirmwareRevision : GattOp
    data object CreateBond : GattOp
    data object EnableIndications : GattOp
    data class RequestMtu(val mtu: Int) : GattOp
    class WriteRx(val value: ByteArray) : GattOp
}

sealed interface GattResult {
    data class Ok(val value: Any?) : GattResult
    data object Failed : GattResult
    data object TimedOut : GattResult
}

/** Zakończenie operacji; [startedAt] — chwila faktycznego startu (bez czasu czekania w kolejce). */
data class GattCompletion(val op: GattOp, val result: GattResult, val startedAt: Long)

/**
 * Jedna operacja GATT naraz na uchwyt. Zakończenie bieżącej przez pasujące zdarzenie ([onEvent]) albo upływ
 * czasu ([onTimeout] — odliczanie prowadzi wołający według [currentTimeoutMs] i [generation]).
 *
 * Zdarzenia radia nie niosą identyfikatora operacji, więc po przeterminowaniu (lub [clear] w trakcie operacji)
 * kolejka czeka na spóźniony callback tej operacji albo na karencję równą jej timeoutowi — dopiero potem startuje
 * następną. Spóźniony callback nie zakończy więc cudzej operacji tego samego typu. Wskazania TX nie kończą żadnej
 * operacji. Nie jest bezpieczna wątkowo — używać z aktora.
 */
class GattQueue(
    private val radio: BleRadio,
    private val handle: RadioHandle,
    private val clock: () -> Long,
) {
    private val pending = ArrayDeque<GattOp>()
    private var startedAt = 0L

    /** Operacja, której callback może jeszcze przyjść (po timeoucie lub [clear]); blokuje start następnej. */
    private var stale: GattOp? = null

    var current: GattOp? = null
        private set

    /** Zmienia się przy każdym starcie operacji i każdym rozpoczęciu karencji — wołający przezbraja timer. */
    var generation = 0
        private set

    val currentTimeoutMs: Long? get() = (current ?: stale)?.let(::timeoutOf)

    fun enqueue(op: GattOp) {
        pending.addLast(op)
        if (current == null) startNext()
    }

    /**
     * Wynik operacji, której dotyczy zdarzenie, albo `null`. Także przy `null` kolejka mogła wystartować następną
     * operację (spóźniony callback porzuconej) — wołający uzgadnia timer wg [generation].
     */
    fun onEvent(e: RadioEvent): GattCompletion? {
        if (e !is RadioEvent.OfHandle || e.handle != handle) return null
        stale?.let { old ->
            if (resultFor(old, e) != null) {
                stale = null // spóźniony callback porzuconej operacji — konsumowany bez wyniku
                startNext()
            }
            return null
        }
        val op = current ?: return null
        val result = resultFor(op, e) ?: return null
        val started = startedAt
        current = null
        startNext()
        return GattCompletion(op, result, started)
    }

    /** Upływ czasu bieżącej operacji (wynik `TimedOut`) albo koniec karencji (`null`, start następnej). */
    fun onTimeout(): GattCompletion? {
        if (stale != null && current == null) {
            stale = null
            startNext()
            return null
        }
        val op = current ?: return null
        current = null
        stale = op
        generation++
        return GattCompletion(op, GattResult.TimedOut, startedAt)
    }

    /** Porzuca kolejkę; operacja w toku przechodzi w karencję (jej callback może jeszcze przyjść). */
    fun clear() {
        pending.clear()
        current?.let {
            stale = it
            generation++
        }
        current = null
    }

    private fun startNext() {
        if (stale != null) return
        val op = pending.removeFirstOrNull() ?: return
        current = op
        startedAt = clock()
        generation++
        when (op) {
            GattOp.Discover -> radio.discoverServices(handle)
            GattOp.ReadInfo -> radio.readInfo(handle)
            GattOp.ReadFirmwareRevision -> radio.readFirmwareRevision(handle)
            GattOp.CreateBond -> radio.createBond(handle)
            GattOp.EnableIndications -> radio.enableIndications(handle)
            is GattOp.RequestMtu -> radio.requestMtu(handle, op.mtu)
            is GattOp.WriteRx -> radio.writeRx(handle, op.value)
        }
    }

    private fun resultFor(op: GattOp, e: RadioEvent): GattResult? = when {
        op == GattOp.Discover && e is RadioEvent.ServicesDiscovered -> ok(e.ok)
        op == GattOp.ReadInfo && e is RadioEvent.InfoRead -> e.value?.let { GattResult.Ok(it) } ?: GattResult.Failed
        op == GattOp.ReadFirmwareRevision && e is RadioEvent.FirmwareRevisionRead -> e.value?.let { GattResult.Ok(it) } ?: GattResult.Failed
        op == GattOp.CreateBond && e is RadioEvent.BondResult -> ok(e.ok)
        op == GattOp.EnableIndications && e is RadioEvent.IndicationsEnabled -> ok(e.ok)
        op is GattOp.RequestMtu && e is RadioEvent.MtuChanged -> GattResult.Ok(e.mtu)
        op is GattOp.WriteRx && e is RadioEvent.RxWritten -> ok(e.ok)
        else -> null
    }

    private fun ok(success: Boolean): GattResult = if (success) GattResult.Ok(null) else GattResult.Failed

    private fun timeoutOf(op: GattOp): Long = when (op) {
        GattOp.Discover -> 10_000
        GattOp.ReadInfo -> 5_000
        GattOp.ReadFirmwareRevision -> 5_000
        GattOp.CreateBond -> 30_000
        GattOp.EnableIndications -> 5_000
        is GattOp.RequestMtu -> 5_000
        is GattOp.WriteRx -> 5_000
    }
}
