package com.agendek.bramav2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIdTest {

    @Test
    fun closedListHas71StatusEntriesPlusLabels() { // spec 1.4: 44; 1.5: +15 OTA, +4 naprawa bondu, +1 okno z telefonu, +7 telefony z dostępem
        assertEquals(71, MessageId.entries.count { !it.name.startsWith("LABEL_") })
    }

    @Test
    fun noIdDescribesGatePosition() {
        val forbidden = listOf("GATE_OPEN", "GATE_CLOSED", "POSITION_OPEN", "POSITION_CLOSED", "IS_OPEN", "IS_CLOSED")
        assertTrue(MessageId.entries.none { id -> forbidden.any { id.name.contains(it) } })
    }

    @Test
    fun onlyUnknownPositionExists() {
        assertEquals(listOf(GatePosition.UNKNOWN), GatePosition.entries)
    }
}
