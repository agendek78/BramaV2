package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.Texts
import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.agendek.bramav2.domain.Confirmation
import com.agendek.bramav2.domain.CredentialState
import com.agendek.bramav2.domain.UiSource

/** Nawigacja (app_design §3) i wspólne dla wszystkich ekranów: potwierdzenia domeny i jednorazowe komunikaty. */
@Composable
fun BramaNav(vm: GateViewModel, activity: Activity) {
    val nav = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val repo = vm.repository
    // Wstecz nigdy nie zdejmuje ekranu głównego: podwójne dotknięcie „Gotowe” albo „Gotowe” + Wstecz wołały popBackStack dwa razy
    // i zostawiały pusty stos (biały ekran, 17.11.2026).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }

    // „Usuń z telefonu” (i każde inne utracenie poświadczenia): ekran ustawień/aktualizacji/historii nie ma już sensu —
    // wracamy na ekran główny z przyciskiem „Dodaj bramę”. Tylko z ACTIVE: porażka rejestracji (UNCONFIRMED → NONE) ma
    // zostać w kreatorze z komunikatem.
    var previousCredential by remember { mutableStateOf(state.credential) }
    LaunchedEffect(state.credential) {
        if (previousCredential == CredentialState.ACTIVE && state.credential == CredentialState.NONE) {
            nav.popBackStack("main", inclusive = false)
        }
        previousCredential = state.credential
    }

    LaunchedEffect(state.notification) {
        state.notification?.let {
            snackbar.showSnackbar(Texts.text(it))
            repo.dismissNotification()
        }
    }

    state.confirmations[UiSource.PHONE]?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { repo.cancelConfirmation(UiSource.PHONE) },
            text = { Text(state.warnings.joinToString("\n\n") { Texts.text(it) }) },
            confirmButton = {
                TextButton(onClick = {
                    when (confirmation) {
                        Confirmation.DISCONNECT_ARMS_AUTOCLOSE, Confirmation.DISCONNECT_ABORTS_UPDATE ->
                            repo.confirmDisconnect(UiSource.PHONE)
                        Confirmation.REMOVE_FROM_PHONE -> repo.confirmRemoveFromPhone()
                        Confirmation.ENABLE_AUTO_CLOSE -> repo.confirmEnableAutoClose()
                    }
                }) { Text(Labels.CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { repo.cancelConfirmation(UiSource.PHONE) }) { Text(Labels.CANCEL) } },
        )
    }

    // Snackbar nad wszystkimi ekranami: jednorazowy komunikat (np. REBOOT_DISARMED) nie może zniknąć niewidoczny.
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "main") {
            composable("main") {
                MainScreen(
                    state = state,
                    repo = repo,
                    onAdd = { nav.navigate("add") },
                    onSettings = { nav.navigate("settings") },
                    onHistory = { nav.navigate("history") },
                )
            }
            composable("add") { AddGateScreen(state, repo, activity, onDone = back) }
            composable("settings") {
                SettingsScreen(
                    state, repo, activity, onBack = back,
                    onFirmware = { nav.navigate("firmware") },
                    onClients = { nav.navigate("clients") },
                )
            }
            composable("clients") { ClientsScreen(state, repo, onBack = back) }
            composable("firmware") { FirmwareUpdateScreen(state, repo, onBack = back) }
            composable("history") {
                val entries by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
                HistoryScreen(entries, onBack = back)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }
}
