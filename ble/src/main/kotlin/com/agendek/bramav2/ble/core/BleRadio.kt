package com.agendek.bramav2.ble.core

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Uchwyt jednego połączenia radiowego (jednego obiektu GATT). Callbacki starych uchwytów są odrzucane. */
@JvmInline
value class RadioHandle(val id: Long)

/**
 * Port do warstwy radiowej BLE (w produkcji `AndroidBleRadio`, w testach symulator). Wszystkie operacje są
 * asynchroniczne; wyniki przychodzą jako [RadioEvent]. Wołający wykonuje najwyżej jedną operację GATT naraz
 * na uchwyt ([GattQueue]). Nie jest bezpieczny wątkowo po stronie wołającego — używać z aktora `BleGateLink`.
 */
interface BleRadio {
    val events: Flow<RadioEvent>

    /** Skan z filtrem UUID usługi; wyniki jako [RadioEvent.ScanResult]. */
    fun startScan(serviceUuid: UUID)

    fun stopScan()

    /** `connectGatt` do adresu; zwraca nowy uchwyt, zdarzenie [RadioEvent.Connected] po zestawieniu łącza. */
    fun connect(address: String): RadioHandle

    fun isBonded(address: String): Boolean

    fun discoverServices(h: RadioHandle)

    fun readInfo(h: RadioHandle)

    /** Odczyt Firmware Revision String z Device Information (bez szyfrowania łącza, spec 8.2). */
    fun readFirmwareRevision(h: RadioHandle)

    fun createBond(h: RadioHandle)

    /**
     * Usuwa bond systemowy z danym urządzeniem (jak „Zapomnij urządzenie” w ustawieniach Bluetooth). Używane, gdy rejestracja
     * kończy się porażką albo użytkownik usuwa bramę z telefonu: bond bez wpisu w sterowniku psuje kolejne parowanie
     * (sterownik odpowiada Security Request na stary bond, telefon go nie ma, parowanie pada). Bez wyniku i bez zdarzeń;
     * brak bondu albo brak możliwości usunięcia to nie błąd.
     */
    fun removeBond(address: String)

    /** Zapis CCCD włączający wskazania TX (wymaga zaszyfrowanego łącza). */
    fun enableIndications(h: RadioHandle)

    fun requestMtu(h: RadioHandle, mtu: Int)

    /** Write Request do RX (spec 8.2: bez Write Without Response). */
    fun writeRx(h: RadioHandle, value: ByteArray)

    /** Rozłącza i zamyka obiekt GATT; po powrocie żadnych zdarzeń tego uchwytu. */
    fun disconnect(h: RadioHandle)
}

sealed interface RadioEvent {
    data class ScanResult(val address: String) : RadioEvent

    sealed interface OfHandle : RadioEvent {
        val handle: RadioHandle
    }

    data class Connected(override val handle: RadioHandle) : OfHandle
    data class Disconnected(override val handle: RadioHandle) : OfHandle
    data class ServicesDiscovered(override val handle: RadioHandle, val ok: Boolean) : OfHandle
    class InfoRead(override val handle: RadioHandle, val value: ByteArray?) : OfHandle

    /** Wartość Firmware Revision String albo `null` (brak usługi, błąd odczytu). */
    class FirmwareRevisionRead(override val handle: RadioHandle, val value: ByteArray?) : OfHandle
    data class BondResult(override val handle: RadioHandle, val ok: Boolean) : OfHandle
    data class IndicationsEnabled(override val handle: RadioHandle, val ok: Boolean) : OfHandle
    data class MtuChanged(override val handle: RadioHandle, val mtu: Int) : OfHandle
    data class RxWritten(override val handle: RadioHandle, val ok: Boolean) : OfHandle
    class TxIndication(override val handle: RadioHandle, val value: ByteArray) : OfHandle
}
