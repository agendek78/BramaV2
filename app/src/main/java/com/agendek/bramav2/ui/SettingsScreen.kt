package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.Texts
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.domain.ConnectionState
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.UiMessage
import com.agendek.bramav2.system.SystemStatus

/**
 * Ustawienia (spec 4.1, 4.3 pkt 7, 15.5): autozamykanie, powiadomienie w zasięgu, nazwa, wersja i aktualizacja
 * firmware, instrukcja resetu, usunięcie.
 */
@Composable
fun SettingsScreen(
    state: GateUiState,
    repo: GateRepository,
    activity: Activity,
    onBack: () -> Unit,
    onFirmware: () -> Unit,
    onClients: () -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(state.gateName.orEmpty()) }
    val scope = rememberCoroutineScope()
    // Przejście do ekranu aktualizacji dopiero, gdy domena obsłużyła wybrany plik (potwierdzenie albo błąd pliku).
    var awaitingFile by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(awaitingFile, state.firmwareUpdate) {
        if (awaitingFile && state.firmwareUpdate != null) {
            awaitingFile = false
            onFirmware()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = readFirmwareFile(context, uri)
            awaitingFile = true
            repo.selectFirmware(bytes)
        }
    }
    Scaffold(
        topBar = { BackBar(Labels.SETTINGS, onBack) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.brandBackground(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
                SectionCard(title = Labels.SECTION_BEHAVIOR) {
                    SwitchRow(Labels.AUTO_CLOSE, state.nextOpenAutoClose, repo::setAutoCloseDefault)
                    SwitchRow(Labels.PRESENCE, state.presenceNotificationEnabled, repo::setPresenceNotificationEnabled)
                    if (state.presenceNotificationEnabled && !SystemStatus.notificationsAllowed(context)) {
                        Text(Labels.PRESENCE_NEEDS_PERMISSION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    // Pominięte lub nieudane powiązanie po rejestracji: bez niego przełącznik nic nie robi (app_design §5).
                    if (state.presenceNotificationEnabled && state.credential == CredentialState.ACTIVE) {
                        CompanionPrompt(
                            activity,
                            ready = state.connection == ConnectionState.READY,
                            explanation = Labels.PRESENCE_NEEDS_ASSOCIATION,
                        )
                    }
                }
                SectionCard(title = Labels.SECTION_GATE) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text(Labels.GATE_NAME) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryAction(Labels.SAVE, { repo.setGateName(name) })
                }
                if (state.credential == CredentialState.ACTIVE) {
                    SectionCard(title = Labels.FIRMWARE) {
                        Text("${Labels.FIRMWARE_VERSION}: ${state.firmwareVersion ?: Labels.VERSION_UNKNOWN}")
                        if (firmwareInProgress(state)) {
                            SecondaryAction(Labels.FIRMWARE_UPDATE, onFirmware)
                        } else {
                            SecondaryAction(Labels.UPDATE_FIRMWARE, { pickFile.launch(arrayOf("*/*")) }, enabled = state.firmwareUpdateAvailable)
                        }
                    }
                    SectionCard(title = Labels.SECTION_CLIENTS) {
                        SecondaryAction(Labels.CLIENTS_OPEN, onClients, enabled = state.connection == ConnectionState.READY)
                        Text(Labels.CLIENTS_HINT, style = MaterialTheme.typography.bodySmall)
                    }
                    // Okno rejestracji bez dostępu do przycisku płytki (phone_window_design §5) — tylko przy bicie 7.
                    if (state.pairingWindowSupported) {
                        SectionCard(title = Labels.SECTION_PAIRING) {
                            SecondaryAction(Labels.OPEN_PAIRING_WINDOW, repo::openPairingWindow, enabled = state.pairingWindowAvailable)
                            Text(Labels.OPEN_PAIRING_WINDOW_HINT, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                SectionCard(title = Labels.RESET_TITLE) {
                    Text(Labels.RESET_STEPS)
                    Text(Texts.text(UiMessage(MessageId.WARN_RESET_NO_SIGNAL)))
                    Text(Labels.RESET_AFTER)
                }
                if (state.credential != CredentialState.NONE) {
                    DangerAction(Labels.REMOVE, repo::requestRemoveFromPhone)
                } else {
                    Text(Labels.FORGET_DEVICE_HINT, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
