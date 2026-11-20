package com.agendek.bramav2.ble.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

/** Atrapa radia zapisująca wywołania (testy kolejki i kanału ramek). */
class RecordingRadio : BleRadio {
    override val events: Flow<RadioEvent> = MutableSharedFlow()
    val calls = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()
    var bonded = true
    private var nextHandle = 1L

    override fun startScan(serviceUuid: UUID) { calls += "scan" }
    override fun stopScan() { calls += "stopScan" }
    override fun connect(address: String): RadioHandle { calls += "connect:$address"; return RadioHandle(nextHandle++) }
    override fun isBonded(address: String) = bonded
    override fun discoverServices(h: RadioHandle) { calls += "discover" }
    override fun readInfo(h: RadioHandle) { calls += "readInfo" }
    override fun readFirmwareRevision(h: RadioHandle) { calls += "readFirmwareRevision" }
    override fun createBond(h: RadioHandle) { calls += "createBond" }
    override fun removeBond(address: String) { calls += "removeBond:$address"; bonded = false }
    override fun enableIndications(h: RadioHandle) { calls += "enableIndications" }
    override fun requestMtu(h: RadioHandle, mtu: Int) { calls += "mtu:$mtu" }
    override fun writeRx(h: RadioHandle, value: ByteArray) { calls += "write"; written += value.copyOf() }
    override fun disconnect(h: RadioHandle) { calls += "disconnect" }
}
