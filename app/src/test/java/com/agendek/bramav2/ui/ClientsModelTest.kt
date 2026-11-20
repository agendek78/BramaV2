package com.agendek.bramav2.ui

import com.agendek.bramav2.protocol.ClientEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiersze ekranu „Telefony z dostępem” (client_management_design.md §5.4). */
class ClientsModelTest {

    private fun entry(tag: Int, name: String, self: Boolean = false, connected: Boolean = false, owner: Boolean = false) =
        ClientEntry(ByteArray(16) { tag.toByte() }, name, self, connected, owner)

    @Test
    fun unnamedPhonesAreNumberedByRegistrationOrder() {
        val rows = clientRows(listOf(entry(1, "Pixel 8", self = true, connected = true), entry(2, ""), entry(3, "Ania")))
        assertEquals(listOf("Pixel 8", "Telefon 2", "Ania"), rows.map { it.title })
    }

    @Test
    fun rowKeepsRealNameEvenWhenItLooksLikeAPlaceholder() {
        val rows = clientRows(listOf(entry(1, "Telefon Ani", self = true), entry(2, "")))
        assertEquals("Telefon Ani", rows[0].name)
        assertEquals("", rows[1].name)
    }

    @Test
    fun badgesFollowFlags() {
        val rows = clientRows(
            listOf(
                entry(1, "A", self = true, connected = true),
                entry(2, "B", connected = true, owner = true),
                entry(3, "C"),
            ),
        )
        assertEquals(listOf("Ten telefon"), rows[0].badges) // własny telefon jest zawsze połączony: bez drugiego znacznika
        assertEquals(listOf("Połączony", "Zarządza bramą"), rows[1].badges)
        assertTrue(rows[2].badges.isEmpty())
    }

    @Test
    fun ownPhoneCanOnlyBeRenamedAndOwnerCannotBeRemoved() {
        val rows = clientRows(listOf(entry(1, "A", self = true, connected = true), entry(2, "B", owner = true), entry(3, "C")))
        assertTrue(rows[0].canRename); assertFalse(rows[0].canRemove)
        assertFalse(rows[1].canRename); assertFalse(rows[1].canRemove)
        assertEquals(Labels.CLIENT_OWNS_RULE_HINT, rows[1].hint)
        assertFalse(rows[2].canRename); assertTrue(rows[2].canRemove)
        assertNull(rows[2].hint)
    }

    @Test
    fun removeDialogMentionsNameAndConsequence() {
        val text = Labels.removeClientDialog("Ania")
        assertTrue(text.contains("Ania"))
        assertTrue(text.contains("straci dostęp"))
        assertTrue(text.contains("od nowa"))
    }

    @Test
    fun defaultPhoneNameJoinsManufacturerAndModelWithoutDuplication() {
        assertEquals("Samsung SM-S911B", defaultPhoneName("samsung", "SM-S911B"))
        assertEquals("Google Pixel 8", defaultPhoneName("Google", "Pixel 8"))
        assertEquals("Google Pixel 8", defaultPhoneName("Google", "Google Pixel 8"))
        assertEquals("Pixel 8", defaultPhoneName("", "Pixel 8"))
        assertEquals("", defaultPhoneName("", ""))
    }
}
