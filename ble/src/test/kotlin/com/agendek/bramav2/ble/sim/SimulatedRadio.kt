package com.agendek.bramav2.ble.sim

import com.agendek.bramav2.ble.core.BleRadio
import com.agendek.bramav2.ble.core.RadioEvent
import com.agendek.bramav2.ble.core.RadioHandle
import com.agendek.bramav2.protocol.AutoClosePhase
import com.agendek.bramav2.protocol.Fragmenter
import com.agendek.bramav2.protocol.Reassembler
import com.agendek.bramav2.protocol.ReassemblyResult
import com.agendek.bramav2.protocol.TimingBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Poprawne INFO (spec 8.2) dla danego `device_id`. */
fun info(deviceId: ByteArray, capabilities: Int = 0xFF): ByteArray = byteArrayOf(2) + deviceId + byteArrayOf(capabilities.toByte(), 8, 32)

/**
 * Radio BLE połączone z [SimulatedGate]. Zdarzenia przychodzą po [intervalMs] (wirtualny czas), wskazania
 * odpowiedzi kolejno co [intervalMs]. Zapisy RX są składane i przekazywane do sterownika tylko na łączu
 * z urządzeniem o `device_id` sterownika. Po `disconnect` uchwytu nie ma już jego zdarzeń.
 */
class SimulatedRadio(
    private val scope: CoroutineScope,
    private val gate: SimulatedGate,
    private val intervalMs: Long = 100,
) : BleRadio {
    private val flow = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 4096)
    override val events: Flow<RadioEvent> = flow

    var devicesInScan: List<Pair<String, ByteArray>> = listOf(GATE_ADDRESS to info(gate.deviceId))
    var bonded = true
    var negotiatedMtu = 247
    var dropIndications = 0
    var failWrites = false
    var scanFindsNothing = false
    val calls = mutableListOf<String>()

    /** Ile razy każde urządzenie pojawia się w skanie (Android raportuje reklamującego wielokrotnie). */
    var scanRepeats = 1

    /** Adresy, z którymi `connectGatt` nigdy się nie zestawia (np. sterownik sąsiada z filtrem połączeń). */
    val neverConnects = mutableSetOf<String>()

    /** Adresy, z którymi `connectGatt` od razu kończy się rozłączeniem (status 133). */
    val failsImmediately = mutableSetOf<String>()

    /** Zrywa łącze zamiast odpowiedzieć na zapis CCCD (np. nieaktualny bond). */
    var dropOnEnableIndications = false

    /** Dodatkowe opóźnienie callbacku danej operacji (nazwa jak w [calls]: "discover", "readInfo", …). */
    val opDelays = mutableMapOf<String, Long>()

    /** Adresy, z którymi łącze pada w trakcie Discover; adresy, dla których Discover nigdy nie odpowiada. */
    val dropOnDiscover = mutableSetOf<String>()
    val discoverNeverAnswers = mutableSetOf<String>()
    var discoverFails = false

    /** Telefon nigdy nie dostarcza `onMtuChanged`. */
    var mtuNeverAnswers = false

    private var nextHandle = 1L
    private val open = mutableMapOf<RadioHandle, String>()
    private val mtus = mutableMapOf<RadioHandle, Int>()
    private val rx = mutableMapOf<RadioHandle, Reassembler>()
    private var scanning = false
    private var outbound = 0L // kolejne wskazania nie wyprzedzają się

    val currentHandle: RadioHandle? get() = open.keys.lastOrNull()

    override fun startScan(serviceUuid: UUID) {
        calls += "scan"
        scanning = true
        if (scanFindsNothing) return
        repeat(scanRepeats) { round ->
            devicesInScan.forEachIndexed { i, (address, _) ->
                scope.launch {
                    delay(intervalMs * (round * devicesInScan.size + i + 1))
                    if (scanning) flow.emit(RadioEvent.ScanResult(address))
                }
            }
        }
    }

    override fun stopScan() {
        calls += "stopScan"
        scanning = false
    }

    override fun connect(address: String): RadioHandle {
        calls += "connect:$address"
        val h = RadioHandle(nextHandle++)
        open[h] = address
        mtus[h] = 23
        rx[h] = Reassembler { TimingBudget(mtus.getValue(h), 100, 0) }
        when (address) {
            in neverConnects -> Unit
            in failsImmediately -> emit(RadioEvent.Disconnected(h)) // np. status 133 tuż po poprzednim close()
            else -> emit(RadioEvent.Connected(h), opDelays["connect"] ?: 0)
        }
        return h
    }

    override fun isBonded(address: String) = bonded

    override fun discoverServices(h: RadioHandle) {
        calls += "discover"
        val address = open[h] ?: return
        when (address) {
            in dropOnDiscover -> emit(RadioEvent.Disconnected(h))
            in discoverNeverAnswers -> Unit
            else -> emit(RadioEvent.ServicesDiscovered(h, ok = !discoverFails), opDelays["discover"] ?: 0)
        }
    }

    override fun readInfo(h: RadioHandle) {
        calls += "readInfo"
        val address = open[h] ?: return
        emit(RadioEvent.InfoRead(h, devicesInScan.firstOrNull { it.first == address }?.second), opDelays["readInfo"] ?: 0)
    }

    /** Odczyt Device Information kończy się błędem (np. brak usługi). */
    var firmwareRevisionFails = false

    override fun readFirmwareRevision(h: RadioHandle) {
        calls += "readFirmwareRevision"
        if (open[h] == null) return
        val value = if (firmwareRevisionFails) null else gate.firmwareVersion.toByteArray(Charsets.UTF_8)
        emit(RadioEvent.FirmwareRevisionRead(h, value))
    }

    /** Sterownik odrzuca parowanie (np. poza oknem rejestracji, 9.5). */
    var bondRefused = false

    override fun createBond(h: RadioHandle) {
        calls += "createBond"
        bonded = !bondRefused
        emit(RadioEvent.BondResult(h, ok = !bondRefused))
    }

    override fun removeBond(address: String) {
        calls += "removeBond:$address"
        bonded = false
    }

    override fun enableIndications(h: RadioHandle) {
        calls += "enableIndications"
        if (dropOnEnableIndications) {
            emit(RadioEvent.Disconnected(h))
            return
        }
        emit(RadioEvent.IndicationsEnabled(h, ok = bonded))
    }

    override fun requestMtu(h: RadioHandle, mtu: Int) {
        if (mtuNeverAnswers) return // MTU zostaje 23
        mtus[h] = minOf(mtu, negotiatedMtu)
        gate.linkMtu = mtus.getValue(h)
        emit(RadioEvent.MtuChanged(h, mtus.getValue(h)))
    }

    override fun writeRx(h: RadioHandle, value: ByteArray) {
        if (failWrites) {
            emit(RadioEvent.RxWritten(h, ok = false))
            return
        }
        emit(RadioEvent.RxWritten(h, ok = true), opDelays["write"] ?: 0)
        if (!isGate(h)) return
        val frame = (rx.getValue(h).accept(value, 0) as? ReassemblyResult.Complete)?.frame ?: return
        gate.receive(frame)?.let { deliver(h, it) }
        if (gate.restartPending) {
            // Po OTA_OK na END sterownik restartuje się (15.5): zanik łącza po wysłaniu odpowiedzi.
            gate.restartPending = false
            scope.launch {
                delay(RESTART_DELAY_MS)
                gate.restart()
                if (h in open) dropLink()
            }
        }
    }

    override fun disconnect(h: RadioHandle) {
        calls += "disconnect"
        open.remove(h)
    }

    /** Zdarzenie wstrzyknięte bezpośrednio (np. spóźniony callback zamkniętego uchwytu). */
    fun inject(e: RadioEvent) {
        flow.tryEmit(e)
    }

    /** Zanik radia: `Disconnected` bieżącego uchwytu. */
    fun dropLink() {
        val h = currentHandle ?: return
        flow.tryEmit(RadioEvent.Disconnected(h))
        open.remove(h)
    }

    /** Sterownik zmienia regułę i wysyła RULE_STATUS bieżącą sesją. */
    fun pushRuleStatus(phase: AutoClosePhase) {
        val h = currentHandle ?: return
        deliver(h, gate.pushRuleStatus(phase))
    }

    private fun isGate(h: RadioHandle): Boolean {
        val address = open[h] ?: return false
        val bytes = devicesInScan.firstOrNull { it.first == address }?.second ?: return false
        return bytes.copyOfRange(1, 17).contentEquals(gate.deviceId)
    }

    private fun deliver(h: RadioHandle, frame: ByteArray) {
        if (dropIndications > 0) {
            dropIndications--
            return
        }
        val budget = TimingBudget(mtus.getValue(h), 100, 0)
        Fragmenter.fragment(frame, (outbound++ % 255 + 1).toInt(), budget).forEachIndexed { i, fragment ->
            scope.launch {
                delay(intervalMs * (i + 2)) // po callbacku zapisu
                if (h in open) flow.emit(RadioEvent.TxIndication(h, fragment))
            }
        }
    }

    private fun emit(e: RadioEvent.OfHandle, extraDelayMs: Long = 0) {
        scope.launch {
            delay(intervalMs + extraDelayMs)
            if (e.handle in open) flow.emit(e)
        }
    }

    companion object {
        const val GATE_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val RESTART_DELAY_MS = 2_000L
    }
}
