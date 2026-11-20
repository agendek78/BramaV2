package com.agendek.bramav2.ble.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattProfileTest {

    private val deviceId = ByteArray(16) { (it + 1).toByte() }
    private fun info(version: Int = 2, size: Int = 20) =
        (byteArrayOf(version.toByte()) + deviceId + byteArrayOf(0x1F, 8, 32)).copyOf(size)

    @Test
    fun parsesInfo() {
        val i = GateInfo.parse(info())!!
        assertArrayEquals(deviceId, i.deviceId)
        assertEquals(0x1F, i.capabilities)
        assertEquals(8, i.maxClients)
        assertEquals(32, i.maxFrameLen16)
    }

    @Test
    fun rejectsWrongLengthOrVersion() {
        assertNull(GateInfo.parse(info(size = 19)))
        assertNull(GateInfo.parse(info(size = 21)))
        assertNull(GateInfo.parse(info(version = 1)))
    }

    @Test
    fun otaBitIsBit6() {
        assertEquals(false, GateInfo.parse(info())!!.supportsOta) // 0x1F
        val withOta = info().also { it[17] = 0x7F }
        assertEquals(true, GateInfo.parse(withOta)!!.supportsOta)
    }

    @Test
    fun phoneWindowBitIsBit7() { // phone_window_design §3
        assertEquals(false, GateInfo.parse(info().also { it[17] = 0x7F })!!.supportsPhoneWindow)
        assertEquals(true, GateInfo.parse(info().also { it[17] = 0xFF.toByte() })!!.supportsPhoneWindow)
    }

    @Test
    fun firmwareRevisionIsUtf8UpToNul() {
        assertEquals("0.2.0", GattProfile.firmwareRevisionOf("0.2.0".toByteArray()))
        assertEquals("0.2.0", GattProfile.firmwareRevisionOf("0.2.0".toByteArray() + byteArrayOf(0, 0x41)))
        assertNull(GattProfile.firmwareRevisionOf(ByteArray(0)))
        assertNull(GattProfile.firmwareRevisionOf(null))
    }

    @Test
    fun deviceInformationUuids() {
        assertEquals("0000180a-0000-1000-8000-00805f9b34fb", GattProfile.DEVICE_INFORMATION.toString())
        assertEquals("00002a26-0000-1000-8000-00805f9b34fb", GattProfile.FIRMWARE_REVISION.toString())
    }

    @Test
    fun uuidsMatchSpec() {
        assertEquals("a8f70001-6a43-4ff6-9ad8-98f63412b001", GattProfile.SERVICE.toString())
        assertEquals("a8f70002-6a43-4ff6-9ad8-98f63412b001", GattProfile.INFO.toString())
        assertEquals("a8f70003-6a43-4ff6-9ad8-98f63412b001", GattProfile.RX.toString())
        assertEquals("a8f70004-6a43-4ff6-9ad8-98f63412b001", GattProfile.TX.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", GattProfile.CCCD.toString())
    }
}
