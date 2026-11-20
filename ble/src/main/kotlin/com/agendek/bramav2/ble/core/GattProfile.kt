package com.agendek.bramav2.ble.core

import java.util.UUID

/** Profil GATT bramy (spec 8.2) — UUID stałe dla protokołu v2. */
object GattProfile {
    val SERVICE: UUID = UUID.fromString("a8f70001-6a43-4ff6-9ad8-98f63412b001")
    val INFO: UUID = UUID.fromString("a8f70002-6a43-4ff6-9ad8-98f63412b001")
    val RX: UUID = UUID.fromString("a8f70003-6a43-4ff6-9ad8-98f63412b001")
    val TX: UUID = UUID.fromString("a8f70004-6a43-4ff6-9ad8-98f63412b001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Standardowa usługa Device Information i Firmware Revision String (spec 8.2, 15.5). */
    val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    const val INFO_SIZE = 20
    const val PROTOCOL_VERSION = 2
    const val REQUESTED_MTU = 247
    const val MIN_MTU = 23

    /** Wersja z Firmware Revision String (`esp_app_desc_t.version`): UTF-8 do pierwszego NUL; pusta → `null`. */
    fun firmwareRevisionOf(value: ByteArray?): String? {
        value ?: return null
        val end = value.indexOf(0).let { if (it < 0) value.size else it }
        return String(value, 0, end, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}

/** Publiczne INFO (spec 8.2). Nie jest dowodem tożsamości — tę potwierdza dopiero handshake. */
class GateInfo(val deviceId: ByteArray, val capabilities: Int, val maxClients: Int, val maxFrameLen16: Int) {
    /** Bit 5 `capabilities`: rejestracja PIN-em przez EC-JPAKE (spec 8.2, 11.4). */
    val supportsPin: Boolean get() = capabilities and 0x20 != 0

    /** Bit 6 `capabilities`: aktualizacja firmware przez BLE (spec 8.2, 15.5). */
    val supportsOta: Boolean get() = capabilities and 0x40 != 0

    /** Bit 7 `capabilities`: okno rejestracji otwierane z zarejestrowanego telefonu (phone_window_design §3). */
    val supportsPhoneWindow: Boolean get() = capabilities and 0x80 != 0

    companion object {
        /**
         * `device_id` z INFO niezależnie od wersji protokołu (bajty 1..16 — założenie: układ początku INFO jest
         * stały między wersjami) albo `null` dla wartości za krótkiej.
         */
        fun deviceIdOf(value: ByteArray): ByteArray? = if (value.size >= 17) value.copyOfRange(1, 17) else null

        fun parse(value: ByteArray): GateInfo? {
            if (value.size != GattProfile.INFO_SIZE || value[0].toInt() != GattProfile.PROTOCOL_VERSION) return null
            return GateInfo(
                deviceId = value.copyOfRange(1, 17),
                capabilities = value[17].toInt() and 0xff,
                maxClients = value[18].toInt() and 0xff,
                maxFrameLen16 = value[19].toInt() and 0xff,
            )
        }
    }
}
