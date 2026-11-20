package com.agendek.bramav2.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.agendek.bramav2.ble.core.BleRadio
import com.agendek.bramav2.ble.core.GattProfile
import com.agendek.bramav2.ble.core.RadioEvent
import com.agendek.bramav2.ble.core.RadioHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [BleRadio] nad `BluetoothGatt` (spec 8). Cienka warstwa bez logiki: każdy callback Androida staje się
 * [RadioEvent] z uchwytem; callbacki zamkniętych uchwytów są odrzucane. Uprawnienia `BLUETOOTH_SCAN`/
 * `BLUETOOTH_CONNECT` zapewnia `:app` (spec 7); `SecurityException` kończy operację porażką.
 * Brak testów automatycznych — emulator nie wspiera BLE (ble_design §5).
 */
@SuppressLint("MissingPermission")
class AndroidBleRadio(context: Context) : BleRadio {

    private companion object {
        const val BOND_POLL_MS = 500L
        const val BOND_BROADCAST_GRACE_MS = 6000L
    }

    private val context = context.applicationContext

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val flow = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 1024)
    override val events: Flow<RadioEvent> = flow

    private val nextHandle = AtomicLong(0)
    private val gatts = ConcurrentHashMap<Long, BluetoothGatt>()

    /**
     * Uchwyty otwarte (od przed `connectGatt` do `disconnect`): callbacki mogą przyjść, zanim `connectGatt`
     * zwróci obiekt i trafi on do [gatts] — nie wolno ich wtedy odrzucić.
     */
    private val alive = ConcurrentHashMap.newKeySet<Long>()
    private val bondWaits = ConcurrentHashMap<String, RadioHandle>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Urządzenia z wyników skanu: niosą właściwy typ adresu (ESP32 może używać static random, spec 8.3),
     * którego `getRemoteDevice(address)` nie zna.
     * Mapa nie jest czyszczona: adres zapamiętany w `BleGateLink` przeżywa skany innych sterowników; skan filtruje po
     * UUID usługi bramy, więc mapa pozostaje mała.
     */
    private val scanned = ConcurrentHashMap<String, BluetoothDevice>()
    private val scanCallback = AtomicReference<ScanCallback?>(null)

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val handle = bondWaits[device.address] ?: return
            when (state) {
                BluetoothDevice.BOND_BONDED -> finishBond(device.address, handle, ok = true)
                BluetoothDevice.BOND_NONE -> finishBond(device.address, handle, ok = false)
            }
        }
    }

    init {
        // ACTION_BOND_STATE_CHANGED to chroniony broadcast systemu (nadawca: proces Bluetooth, inny UID niż aplikacja), więc
        // sfałszować go nie może inna aplikacja. Z RECEIVER_NOT_EXPORTED nie dotarł na telefonie testowym (log: bond
        // wykryty dopiero odpytywaniem), dlatego RECEIVER_EXPORTED; odpytywanie (pollBond) zostaje jako zabezpieczenie.
        this.context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
    }

    override fun startScan(serviceUuid: UUID) {
        stopScan() // poprzedni skan nie może zostać zarejestrowany bez callbacku do zatrzymania
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanCallback.get() !== this) return // spóźniony wynik zatrzymanego skanu
                scanned[result.device.address] = result.device
                flow.tryEmit(RadioEvent.ScanResult(result.device.address))
            }

            override fun onScanFailed(errorCode: Int) {
                // Np. ograniczenie liczby startów skanu: brak wyników, próbę zakończy timeout skanu w BleGateLink.
                // Tylko własny callback — nowszy skan mógł już zarejestrować swój.
                scanCallback.compareAndSet(this, null)
            }
        }
        scanCallback.set(callback)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, callback) }
    }

    override fun stopScan() {
        val callback = scanCallback.getAndSet(null) ?: return
        runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
    }

    override fun connect(address: String): RadioHandle {
        val handle = RadioHandle(nextHandle.incrementAndGet())
        alive += handle.id
        val gatt = runCatching {
            // Wariant z BluetoothGattConnectionSettings istnieje dopiero od API 37; minSdk = 34 wymaga tego.
            @Suppress("DEPRECATION")
            device(address).connectGatt(context, false, Callback(handle), BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            alive -= handle.id
            flow.tryEmit(RadioEvent.Disconnected(handle))
        } else {
            gatts[handle.id] = gatt
            // disconnect()/close() z innego wątku w trakcie connectGatt: GATT nie trafił jeszcze do mapy — zamknąć tu.
            if (!alive.contains(handle.id) && gatts.remove(handle.id, gatt)) runCatching { gatt.close() }
        }
        return handle
    }

    /** Urządzenie z powiązania CDM (z właściwym typem adresu) — jak wynik skanu, bez skanowania. */
    fun registerDevice(device: BluetoothDevice) {
        scanned[device.address] = device
    }

    override fun isBonded(address: String): Boolean =
        runCatching { device(address).bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    override fun discoverServices(h: RadioHandle) {
        if (gatt(h)?.let { runCatching { it.discoverServices() }.getOrDefault(false) } != true) {
            emit(h, RadioEvent.ServicesDiscovered(h, ok = false))
        }
    }

    override fun readInfo(h: RadioHandle) {
        val ok = gatt(h)?.let { g -> characteristic(g, GattProfile.INFO)?.let { runCatching { g.readCharacteristic(it) }.getOrDefault(false) } }
        if (ok != true) emit(h, RadioEvent.InfoRead(h, null))
    }

    override fun readFirmwareRevision(h: RadioHandle) {
        val ok = gatt(h)?.let { g ->
            g.getService(GattProfile.DEVICE_INFORMATION)?.getCharacteristic(GattProfile.FIRMWARE_REVISION)
                ?.let { runCatching { g.readCharacteristic(it) }.getOrDefault(false) }
        }
        if (ok != true) emit(h, RadioEvent.FirmwareRevisionRead(h, null))
    }

    override fun createBond(h: RadioHandle) {
        val device = gatt(h)?.device ?: return emit(h, RadioEvent.BondResult(h, ok = false))
        bondWaits[device.address] = h
        val started = runCatching { device.createBond() }.getOrDefault(false)
        // Sterownik wysyła Security Request tuż po połączeniu, więc stos telefonu bywa już w BOND_BONDING, zanim aplikacja
        // wywoła createBond(); wtedy zwraca ono false (Android 13, log 13.11.2026), choć parowanie trwa. Porażką jest tylko
        // false przy braku trwającego bondu.
        val state = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        if (!started && state == BluetoothDevice.BOND_NONE) {
            finishBond(device.address, h, ok = false)
            return
        }
        pollBond(device, h, sawBonding = state == BluetoothDevice.BOND_BONDING)
    }

    /**
     * Zabezpieczenie na wypadek niedostarczonego `ACTION_BOND_STATE_CHANGED`. Autorytatywny jest broadcast: na telefonie
     * testowym `bondState` pokazał BONDED ok. 2,8 s przed broadcastem BONDED (drugie systemowe pytanie o parowanie), a zapis
     * RX wysłany w tej luce nie dostał odpowiedzi. Dlatego odpytywanie uznaje BONDED dopiero, gdy broadcast nie nadejdzie
     * przez [BOND_BROADCAST_GRACE_MS] od pierwszego zaobserwowania BONDED.
     */
    private fun pollBond(device: BluetoothDevice, h: RadioHandle, sawBonding: Boolean, bondedSinceMs: Long = 0L) {
        mainHandler.postDelayed({
            if (bondWaits[device.address] != h) return@postDelayed // zakończone przez broadcast lub rozłączenie
            val now = android.os.SystemClock.elapsedRealtime()
            when (runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    val since = if (bondedSinceMs == 0L) now else bondedSinceMs
                    if (now - since >= BOND_BROADCAST_GRACE_MS) {
                        finishBond(device.address, h, ok = true)
                    } else {
                        pollBond(device, h, sawBonding, since)
                    }
                }
                BluetoothDevice.BOND_BONDING -> pollBond(device, h, sawBonding = true)
                else -> if (sawBonding) {
                    finishBond(device.address, h, ok = false)
                } else {
                    pollBond(device, h, sawBonding = false)
                }
            }
        }, BOND_POLL_MS)
    }

    /**
     * `BluetoothDevice.removeBond()` jest ukrytym API (@hide), bez publicznego odpowiednika dla aplikacji: wołamy je
     * refleksją. Gdy system je zablokuje albo bond nie istnieje, nic się nie dzieje (użytkownik może zapomnieć urządzenie
     * w ustawieniach Bluetooth).
     */
    override fun removeBond(address: String) {
        runCatching {
            val device = device(address)
            if (device.bondState == BluetoothDevice.BOND_NONE) return
            BluetoothDevice::class.java.getMethod("removeBond").invoke(device)
        }
    }

    override fun enableIndications(h: RadioHandle) {
        val g = gatt(h)
        val tx = g?.let { characteristic(it, GattProfile.TX) }
        val descriptor = tx?.getDescriptor(GattProfile.CCCD)
        val status = if (g != null && tx != null && descriptor != null) {
            runCatching {
                if (g.setCharacteristicNotification(tx, true)) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                } else {
                    -1
                }
            }.getOrDefault(-1)
        } else {
            -1
        }
        if (status != BluetoothStatusCodes.SUCCESS) emit(h, RadioEvent.IndicationsEnabled(h, ok = false))
    }

    override fun requestMtu(h: RadioHandle, mtu: Int) {
        if (gatt(h)?.let { runCatching { it.requestMtu(mtu) }.getOrDefault(false) } != true) {
            emit(h, RadioEvent.MtuChanged(h, GattProfile.MIN_MTU))
        }
    }

    override fun writeRx(h: RadioHandle, value: ByteArray) {
        val g = gatt(h)
        val rx = g?.let { characteristic(it, GattProfile.RX) }
        val status = if (g != null && rx != null) {
            runCatching { g.writeCharacteristic(rx, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }.getOrDefault(-1)
        } else {
            -1
        }
        if (status != BluetoothStatusCodes.SUCCESS) emit(h, RadioEvent.RxWritten(h, ok = false))
    }

    override fun disconnect(h: RadioHandle) {
        if (!alive.remove(h.id)) return
        bondWaits.entries.removeIf { it.value == h }
        val g = gatts.remove(h.id) ?: return
        runCatching {
            g.disconnect()
            g.close()
        }
    }

    private fun finishBond(address: String, h: RadioHandle, ok: Boolean) {
        bondWaits.remove(address, h)
        emit(h, RadioEvent.BondResult(h, ok))
    }

    private fun gatt(h: RadioHandle): BluetoothGatt? = gatts[h.id]

    private fun device(address: String): BluetoothDevice = scanned[address] ?: adapter.getRemoteDevice(address)

    /** Zwalnia zasoby radia (odbiornik bondów, skan, połączenia). */
    fun close() {
        stopScan()
        alive.toList().forEach { disconnect(RadioHandle(it)) }
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    private fun characteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.getService(GattProfile.SERVICE)?.getCharacteristic(uuid)

    /** Zdarzenie tylko dla otwartego uchwytu (po `disconnect` żadnych zdarzeń). */
    private fun emit(h: RadioHandle, event: RadioEvent) {
        if (alive.contains(h.id)) flow.tryEmit(event)
    }

    private inner class Callback(private val handle: RadioHandle) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            emit(handle, if (connected) RadioEvent.Connected(handle) else RadioEvent.Disconnected(handle))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS && gatt.getService(GattProfile.SERVICE) != null
            emit(handle, RadioEvent.ServicesDiscovered(handle, ok))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val read = value.takeIf { status == BluetoothGatt.GATT_SUCCESS }
            emit(
                handle,
                if (characteristic.uuid == GattProfile.FIRMWARE_REVISION) {
                    RadioEvent.FirmwareRevisionRead(handle, read)
                } else {
                    RadioEvent.InfoRead(handle, read)
                },
            )
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            emit(handle, RadioEvent.IndicationsEnabled(handle, status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            emit(handle, RadioEvent.MtuChanged(handle, if (status == BluetoothGatt.GATT_SUCCESS) mtu else GattProfile.MIN_MTU))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            emit(handle, RadioEvent.RxWritten(handle, status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == GattProfile.TX) emit(handle, RadioEvent.TxIndication(handle, value.copyOf()))
        }
    }
}
