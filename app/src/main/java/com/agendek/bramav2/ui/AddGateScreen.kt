package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.Texts
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.agendek.bramav2.ble.core.PinAvailability
import com.agendek.bramav2.graph
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.MessageId
import com.agendek.bramav2.domain.RegistrationState
import com.agendek.bramav2.domain.UiMessage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Kreator „Dodaj bramę” (spec 4.1, 11.3, 11.4): instrukcja → QR (skaner Google) albo PIN → postęp → powiązanie. */
@Composable
fun AddGateScreen(state: GateUiState, repo: GateRepository, activity: Activity, onDone: () -> Unit) {
    // PIN tylko w pamięci (spec 9.2): bez `rememberSaveable`, który kopiuje stan do zapisanego stanu aktywności.
    var pin by remember { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf<String?>(null) }
    val registration = state.registration
    val inProgress = registration is RegistrationState.Pairing || registration is RegistrationState.SavingCredential ||
        registration is RegistrationState.Committing || registration is RegistrationState.ConfirmingSession

    // Dostępność PIN-u z reklamy sterownika (okno otwarte): skan tylko na ekranie wyboru, nie w trakcie rejestracji.
    var pinAvailability by remember { mutableStateOf<PinAvailability>(PinAvailability.Unknown) }
    LaunchedEffect(inProgress, registration == RegistrationState.Done) {
        if (inProgress || registration == RegistrationState.Done) return@LaunchedEffect
        activity.graph.gateWatcher.pinAvailability().collect { pinAvailability = it }
    }
    val pinBlocked = pinAvailability == PinAvailability.Unavailable

    // Wyjście zawsze zamyka rejestrację: trwającą przerywa, a wynik (Done, Failed, InvalidQr) czyści —
    // inaczej następne wejście do kreatora pokazałoby stary wynik.
    val leave = {
        repo.cancelRegistration()
        onDone()
    }
    BackHandler(onBack = leave)
    Scaffold(
        topBar = { BackBar(Labels.ADD_GATE, leave) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.brandBackground(),
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            // W trakcie rejestracji grafika i napis są wyśrodkowane w pionie, w pozostałych stanach kolumna się przewija.
            ScreenColumn(
                if (inProgress) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = if (inProgress) Arrangement.Center else Arrangement.spacedBy(16.dp),
            ) {
                when {
                    registration == RegistrationState.Done -> {
                        IllustrationHeader(ConnectionTone.CONNECTED)
                        SectionCard {
                            Text(Texts.text(UiMessage(MessageId.REGISTRATION_DONE)), style = MaterialTheme.typography.titleMedium)
                            CompanionPrompt(activity)
                        }
                        PrimaryAction(Labels.DONE, leave)
                    }
                    inProgress -> {
                        IllustrationHeader(ConnectionTone.CONNECTING, size = 176.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
                        Text(
                            Labels.REGISTERING,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    else -> {
                        IllustrationHeader(ConnectionTone.OFFLINE)
                        SectionCard {
                            Text(Labels.ADD_INTRO)
                            when (registration) {
                                is RegistrationState.Failed -> {
                                    Text(Texts.text(registration.message), color = MaterialTheme.colorScheme.error, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    Text(Labels.FORGET_DEVICE_HINT, style = MaterialTheme.typography.bodySmall)
                                }
                                is RegistrationState.InvalidQr -> Text(Labels.INVALID_QR, color = MaterialTheme.colorScheme.error)
                                else -> Unit
                            }
                            hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        PrimaryAction(Labels.SCAN_QR, {
                            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                            GmsBarcodeScanning.getClient(activity, options).startScan()
                                .addOnSuccessListener { code -> code.rawValue?.let(repo::startRegistration) }
                                .addOnFailureListener { hint = Labels.SCANNER_UNAVAILABLE }
                        })
                        SectionCard(title = Labels.PIN_SECTION) {
                            if (pinBlocked) Text(Labels.PIN_UNAVAILABLE, color = MaterialTheme.colorScheme.error)
                            (pinAvailability as? PinAvailability.Available)?.let { Text(Labels.pinAttemptsLeft(it.attemptsLeft), style = MaterialTheme.typography.bodyMedium) }
                            OutlinedTextField(
                                value = pin,
                                enabled = !pinBlocked,
                                onValueChange = { v -> pin = v.filter(Char::isDigit).take(6) },
                                label = { Text(Labels.PIN_FIELD) },
                                singleLine = true,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecondaryAction(
                                Labels.REGISTER,
                                { repo.startPinRegistration(pin).also { pin = "" } },
                                enabled = pin.length == 6 && !pinBlocked,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Powiązanie urządzenia towarzyszącego (app_design §5): po rejestracji i z ustawień; pominięcie wyłącza tylko
 * powiadomienie w zasięgu. [ready]: adres sterownika jest znany dopiero po uwierzytelnionej sesji (`:ble`).
 */
@Composable
fun CompanionPrompt(activity: Activity, ready: Boolean = true, explanation: String? = null) {
    val companion = activity.graph.companion
    // `null`: sprawdzanie, czy istniejące powiązanie dotyczy tej bramy (stare jest zastępowane przy powiązaniu).
    var done by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Ponownie po READY: adres z `:ble` (albo z `restore()` przy starcie) mógł nie być jeszcze znany.
    LaunchedEffect(ready) { if (done != true) done = companion.isAssociatedWithGate() }
    if (done == false) {
        explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (failed) Text(Labels.ASSOCIATE_FAILED)
        SecondaryAction(Labels.ASSOCIATE, {
            scope.launch {
                failed = !companion.associate(activity) { ok -> if (ok) done = true else failed = true }
            }
        }, enabled = ready)
    }
}
