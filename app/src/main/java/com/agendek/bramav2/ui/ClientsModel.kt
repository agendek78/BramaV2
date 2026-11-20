package com.agendek.bramav2.ui

import com.agendek.bramav2.protocol.ClientEntry

/** Wiersz listy telefonów: tytuł, znaczniki i dozwolone akcje (client_management_design.md §5.4). */
class ClientRow(
    val clientId: ByteArray,
    /** Nazwa zapisana na sterowniku (pusta, gdy brak) — wartość początkowa okna zmiany nazwy. */
    val name: String,
    val title: String,
    val badges: List<String>,
    val canRename: Boolean,
    val canRemove: Boolean,
    val hint: String?,
)

/** Telefony bez nazwy dostają „Telefon N” wg kolejności rejestracji; usunąć można tylko cudzy telefon, który nie zarządza regułą. */
fun clientRows(entries: List<ClientEntry>): List<ClientRow> = entries.mapIndexed { i, e ->
    ClientRow(
        clientId = e.clientId,
        name = e.name,
        title = e.name.ifBlank { Labels.unnamedClient(i + 1) },
        badges = buildList {
            if (e.isSelf) add(Labels.BADGE_SELF)
            if (e.isConnected && !e.isSelf) add(Labels.BADGE_CONNECTED)
            if (e.ownsRule) add(Labels.BADGE_OWNS_RULE)
        },
        canRename = e.isSelf,
        canRemove = !e.isSelf && !e.ownsRule,
        hint = if (!e.isSelf && e.ownsRule) Labels.CLIENT_OWNS_RULE_HINT else null,
    )
}

/** Nazwa domyślna telefonu wysyłana do sterownika: producent i model, bez powtórzenia producenta (np. „Google Pixel 8”). */
fun defaultPhoneName(manufacturer: String, model: String): String {
    val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
    val name = model.trim()
    return when {
        maker.isEmpty() -> name
        name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name".trim()
    }
}
