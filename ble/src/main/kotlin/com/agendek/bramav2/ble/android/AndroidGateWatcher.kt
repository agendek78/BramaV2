package com.agendek.bramav2.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import com.agendek.bramav2.ble.core.GateAdvertisement
import com.agendek.bramav2.ble.core.GattProfile
import com.agendek.bramav2.ble.core.PinAvailability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Obserwator reklamy sterownika dla kreatora „Dodaj bramę”: własny skan (niezależny od łącza), żeby ekran znał
 * dostępność rejestracji PIN-em, zanim użytkownik wpisze PIN. Działa tylko, dopóki ktoś zbiera przepływ.
 */
@SuppressLint("MissingPermission") // brak uprawnień kończy się wyjątkiem Security: wtedy [PinAvailability.Unknown]
class AndroidGateWatcher(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Dostępność PIN z reklam bramek w zasięgu: stan liczony per urządzenie, wygasa po [STALE_MS] bez reklamy, a wynik to
     * [GateAdvertisement.aggregate] (sąsiednia bramka nie blokuje rejestracji na własnej). Błąd skanu daje [PinAvailability.Unknown].
     */
    fun pinAvailability(): Flow<PinAvailability> = callbackFlow {
        trySend(PinAvailability.Unknown)
        val seen = HashMap<String, Pair<PinAvailability, Long>>() // adres → (stan, czas ostatniej reklamy)
        fun publish() {
            val now = SystemClock.elapsedRealtime()
            seen.entries.removeAll { now - it.value.second > STALE_MS }
            trySend(GateAdvertisement.aggregate(seen.values.map { it.first }))
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(GateAdvertisement.COMPANY_ID)
                val state = GateAdvertisement.pinAvailability(data)
                synchronized(seen) {
                    val changed = seen[result.device.address]?.first != state
                    seen[result.device.address] = state to SystemClock.elapsedRealtime()
                    if (changed) publish() // reklamy co 100–200 ms: bez zmiany stanu tylko odświeżenie czasu (wygasanie robi tykanie)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                // Np. limit startów skanu Androida: bez wyników ekran zostaje na Unknown i niczego nie blokuje.
                trySend(PinAvailability.Unknown)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattProfile.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, callback) }.isSuccess
        val staleCheck = launch {
            while (started) {
                delay(STALE_MS / 2)
                synchronized(seen) { publish() }
            }
        }
        awaitClose {
            staleCheck.cancel()
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
    }.distinctUntilChanged()

    private companion object {
        const val STALE_MS = 5_000L
    }
}
