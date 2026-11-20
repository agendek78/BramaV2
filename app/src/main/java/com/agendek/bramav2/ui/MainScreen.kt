package com.agendek.bramav2.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.agendek.bramav2.ui.theme.BramaColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.UiSource
import com.agendek.bramav2.graph
import com.agendek.bramav2.system.SystemStatus

/**
 * Ekran główny (decyzja właściciela): wyśrodkowana grafika bramy, stan połączenia w „pigułce” z kolorową kropką, duże
 * OTWÓRZ/ZAMKNIJ pośrodku ekranu; bez tekstów statusów pod przyciskami.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: GateUiState,
    repo: GateRepository,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
) {
    val ui = mainUi(state)
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        context.graph.systemStatus.refresh()
        context.graph.refreshPresenceConditions() // zgoda na powiadomienia nie wywołuje ON_START
    }
    // Prośba raz na wejście do aplikacji, nie przy każdym powrocie z innego ekranu; potem przycisk do ustawień.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        val missing = (SystemStatus.BLUETOOTH_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS)
            .filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    val dark = isSystemInDarkTheme()

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(ui.title, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Filled.DateRange, contentDescription = Labels.HISTORY) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = Labels.SETTINGS) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
            )
        },
        modifier = Modifier.background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)),
        ),
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // Środkowanie przy przewijaniu: kolumna ma co najmniej wysokość ekranu, więc Arrangement.Center działa.
            Column(
                Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Przy naprawie połączenia (przycisk i instrukcja) grafika i przyciski są mniejsze, żeby wszystko mieściło się na ekranie.
                val compact = ui.repairBond
                GateIllustration(ui.tone, size = if (compact) 120.dp else 176.dp)
                Spacer(Modifier.height(12.dp))
                StatusPill(ui.connection, ui.tone)
                when (ui.connectAction) {
                    ConnectAction.CONNECT -> TextButton(onClick = repo::connect) { Text(Labels.CONNECT) }
                    ConnectAction.DISCONNECT -> TextButton(onClick = { repo.requestDisconnect(UiSource.PHONE) }) { Text(Labels.DISCONNECT) }
                    ConnectAction.NONE -> Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
                if (ui.repairBond) {
                    FilledTonalButton(
                        onClick = repo::repairBond,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                    ) { Text(Labels.REPAIR_BOND) }
                    // Instrukcja (przycisk sterownika 3 s) — tekst problemu BOND_MISSING / REPAIR_BOND_FAILED (jedyny tekst statusu na ekranie).
                    ui.repairHint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 360.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (ui.needsSetup) {
                    ControlButton(Labels.ADD_GATE, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, true, 88.dp, 22, onAdd)
                } else {
                    ControlButton(
                        Labels.OPEN,
                        BramaColors.open,
                        Color.White,
                        ui.canOpen,
                        if (compact) 92.dp else 112.dp,
                        28,
                        repo::open,
                    )
                    Spacer(Modifier.height(16.dp))
                    ControlButton(
                        Labels.CLOSE,
                        if (dark) BramaColors.closeDark else BramaColors.close,
                        Color.White,
                        ui.canClose,
                        if (compact) 92.dp else 112.dp,
                        28,
                    ) { repo.requestClose(UiSource.PHONE) }
                }
                if (!SystemStatus.bluetoothPermissionsGranted(context)) {
                    Spacer(Modifier.height(12.dp))
                    // Po odmowie system nie pokazuje już dialogu — zostają ustawienia aplikacji (app_design §7).
                    // Powrót z ustawień to ON_START procesu: SystemStatus.refresh() i warunki obecności.
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text(Labels.PERMISSIONS) }
                }
            }
        }
    }
}

/** Stan połączenia: tekst z kolorową kropką w zaokrąglonej „pigułce”. */
@Composable
private fun StatusPill(text: String, tone: ConnectionTone) {
    val color = tone.color()
    Surface(shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Duży zaokrąglony przycisk sterowania: stały kolor, przygaszony gdy niedostępny. */
@Composable
private fun ControlButton(
    text: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    height: androidx.compose.ui.unit.Dp,
    fontSize: Int,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.22f),
            disabledContentColor = content.copy(alpha = 0.55f),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp, disabledElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(height),
    ) {
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}
