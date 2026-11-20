package com.agendek.bramav2.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.domain.FirmwareUpdate
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.protocol.OtaPayloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Ekran aktualizacji firmware (spec 15.5, ota_design §6): potwierdzenie „wersja X → Y”, postęp, ponowienie END,
 * restart i wynik. Wyjście z ekranu nie przerywa transferu — działa on w usłudze połączenia.
 */
@Composable
fun FirmwareUpdateScreen(state: GateUiState, repo: GateRepository, onBack: () -> Unit) {
    val ui = firmwareUi(state)
    // Potwierdzenie i wynik dotyczą tylko tego ekranu; trwający transfer zostaje.
    val leave = {
        when (state.firmwareUpdate) {
            is FirmwareUpdate.Confirm -> repo.cancelFirmwareUpdate()
            is FirmwareUpdate.Finished -> repo.dismissFirmwareUpdate()
            else -> Unit
        }
        onBack()
    }
    BackHandler(onBack = leave)
    Scaffold(
        topBar = { BackBar(Labels.FIRMWARE_UPDATE, leave) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.brandBackground(),
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            ScreenColumn(Modifier.verticalScroll(rememberScrollState())) {
                IllustrationHeader(
                    if (state.firmwareUpdate == FirmwareUpdate.Rebooting || ui.progress != null) ConnectionTone.CONNECTING else ConnectionTone.CONNECTED,
                )
                SectionCard {
                    ui.versionChange?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                    ui.progress?.let { p ->
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                    if (state.firmwareUpdate == FirmwareUpdate.Rebooting) CircularProgressIndicator()
                    ui.texts.forEach { Text(it) }
                }
                ui.actions.forEach { action ->
                    when (action) {
                        FirmwareAction.UPDATE -> PrimaryAction(Labels.UPDATE, repo::confirmFirmwareUpdate, enabled = firmwareConfirmEnabled(state))
                        FirmwareAction.CANCEL -> SecondaryAction(Labels.CANCEL, leave)
                        FirmwareAction.RETRY_END -> PrimaryAction(Labels.RETRY, repo::retryFirmwareEnd)
                        FirmwareAction.CANCEL_UPDATE -> SecondaryAction(Labels.CANCEL_UPDATE, repo::cancelFirmwareUpdate)
                        FirmwareAction.CLOSE -> PrimaryAction(Labels.CLOSE_RESULT, leave)
                    }
                }
            }
        }
    }
}

/**
 * Odczyt pliku wybranego selektorem dokumentów (SAF, bez uprawnień do pamięci). Czyta najwyżej `0x1E0000 + 1` B —
 * większy plik domena odrzuci jako za duży bez czytania całości. Błąd odczytu daje pustą tablicę, którą domena
 * odrzuca tym samym tekstem co zły plik („Wybrany plik nie jest firmware tego sterownika”, ota_design §8 pkt 10).
 */
suspend fun readFirmwareFile(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val limit = OtaPayloads.MAX_IMAGE_SIZE.toInt() + 1
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull() ?: ByteArray(0)
}
