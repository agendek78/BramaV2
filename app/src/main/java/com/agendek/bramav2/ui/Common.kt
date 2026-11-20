package com.agendek.bramav2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Tło ekranów marki: pionowy gradient od koloru wariantu powierzchni do tła. */
@Composable
fun Modifier.brandBackground(): Modifier =
    background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)))

/** Przezroczysty pasek z wyśrodkowanym tytułem i strzałką wstecz (wspólny dla ekranów poza głównym). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = Labels.BACK) }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
    )
}

/** Zawartość ekranu: kolumna ograniczona szerokością (tablety) z odstępami; wołana wewnątrz `Scaffold`. */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().widthIn(max = 560.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Karta sekcji: biała (w ciemnym motywie ciemna) powierzchnia z zaokrągleniem i opcjonalnym tytułem. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

private val ActionShape = RoundedCornerShape(20.dp)

/** Główna akcja ekranu (wypełniona, w kolorze marki). */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ActionShape,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp, disabledElevation = 0.dp),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** Akcja drugorzędna (tonalna). */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = ActionShape, modifier = modifier.fillMaxWidth().height(52.dp)) {
        Text(text)
    }
}

/** Akcja destrukcyjna (kontur w kolorze błędu), np. usunięcie bramy z telefonu. */
@Composable
fun DangerAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ActionShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) { Text(text) }
}

/** Grafika bramy wyśrodkowana nad treścią ekranu. */
@Composable
fun IllustrationHeader(tone: ConnectionTone, size: androidx.compose.ui.unit.Dp = 132.dp) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        GateIllustration(tone, size = size)
    }
}
