package com.agendek.bramav2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.domain.ClientsUi
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.Texts
import com.agendek.bramav2.protocol.ClientName

/**
 * „Telefony z dostępem” (client_management_design.md §5.4): telefony zapisane w sterowniku, usunięcie innego telefonu
 * z potwierdzeniem i zmiana nazwy własnego.
 */
@Composable
fun ClientsScreen(state: GateUiState, repo: GateRepository, onBack: () -> Unit) {
    // Lista nieaktualna (wejście na ekran, po zmianie, po utracie sesji): odśwież, gdy sesja nie ma innego żądania.
    LaunchedEffect(state.clients, state.clientsAvailable) {
        if (state.clients == ClientsUi.Idle && state.clientsAvailable) repo.refreshClients()
    }
    var removing by remember { mutableStateOf<ClientRow?>(null) }
    var renaming by remember { mutableStateOf<ClientRow?>(null) }

    Scaffold(
        topBar = { BackBar(Labels.SECTION_CLIENTS, onBack) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.brandBackground(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
                Text(Labels.CLIENTS_HINT, style = MaterialTheme.typography.bodyMedium)
                when (val clients = state.clients) {
                    ClientsUi.Idle, ClientsUi.Loading ->
                        Text(if (state.connection == ConnectionState.READY) Labels.CLIENTS_LOADING else Labels.CLIENTS_UNAVAILABLE)
                    is ClientsUi.Error -> {
                        Text(Texts.text(clients.message), color = MaterialTheme.colorScheme.error)
                        SecondaryAction(Labels.CLIENTS_REFRESH, repo::refreshClients, enabled = state.clientsAvailable)
                    }
                    is ClientsUi.Ready -> {
                        for (row in clientRows(clients.clients)) {
                            ClientCard(
                                row,
                                actionsEnabled = state.clientsAvailable,
                                onRemove = { removing = row },
                                onRename = { renaming = row },
                            )
                        }
                        SecondaryAction(Labels.CLIENTS_REFRESH, repo::refreshClients, enabled = state.clientsAvailable)
                    }
                }
            }
        }
    }

    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(Labels.removeClientDialog(row.title)) },
            confirmButton = {
                TextButton(enabled = state.clientsAvailable, onClick = {
                    repo.removeClient(row.clientId)
                    removing = null
                }) { Text(Labels.CLIENT_REMOVE, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(Labels.CANCEL) } },
        )
    }
    renaming?.let { row ->
        var text by remember(row) { mutableStateOf(row.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(Labels.CLIENT_RENAME) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text(Labels.CLIENT_NAME_FIELD) },
                        singleLine = true,
                        isError = !ClientName.isValid(text),
                        supportingText = { Text("${text.toByteArray(Charsets.UTF_8).size}/${ClientName.MAX_BYTES}") },
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ClientName.isValid(text),
                    onClick = {
                        repo.renameSelf(text.trim())
                        renaming = null
                    },
                ) { Text(Labels.SAVE) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(Labels.CANCEL) } },
        )
    }
}

@Composable
private fun ClientCard(row: ClientRow, actionsEnabled: Boolean, onRemove: () -> Unit, onRename: () -> Unit) {
    SectionCard {
        Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (row.badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.badges.forEach { Badge(it) } }
        }
        row.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (row.canRename) SecondaryAction(Labels.CLIENT_RENAME, onRename, enabled = actionsEnabled)
        if (row.canRemove) DangerAction(Labels.CLIENT_REMOVE, onRemove, enabled = actionsEnabled)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
