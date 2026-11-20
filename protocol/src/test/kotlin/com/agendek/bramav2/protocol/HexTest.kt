package com.agendek.bramav2.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HexTest {

    @Test
    fun roundTrips() {
        val bytes = byteArrayOf(0, 0x7f, -128, -1, 0x10)
        assertEquals("007f80ff10", bytes.toHex())
        assertArrayEquals(bytes, "007f80ff10".hexToBytes())
    }

    @Test
    fun ignoresSpacesAndAcceptsUppercase() {
        assertArrayEquals(byteArrayOf(0x47, 0x42), "47 42".hexToBytes())
        assertArrayEquals(byteArrayOf(-1), "FF".hexToBytes())
    }

    @Test
    fun rejectsOddLengthAndNonHex() {
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "zz".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "\uFF10\uFF11".hexToBytes() } // cyfry pełnej szerokości
    }
}
