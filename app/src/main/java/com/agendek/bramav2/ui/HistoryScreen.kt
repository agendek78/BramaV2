package com.agendek.bramav2.ui

import com.agendek.bramav2.domain.Texts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.agendek.bramav2.ui.theme.BramaColors
import com.agendek.bramav2.domain.GateCommand
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agendek.bramav2.domain.HistoryEntry
import com.agendek.bramav2.domain.LastCommand
import com.agendek.bramav2.domain.Messages
import java.text.DateFormat
import java.util.Date

/** Historia (spec 4.1): czas, komenda, boolean dla OPEN, wynik — bez sekretów; nie zastępuje czujnika położenia. */
@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onBack: () -> Unit) {
    Scaffold(
        topBar = { BackBar(Labels.HISTORY, onBack) },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.brandBackground(),
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                GateIllustration(ConnectionTone.OFFLINE, size = 132.dp)
                Spacer(Modifier.height(12.dp))
                Text(Labels.HISTORY_EMPTY, style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries) { e ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dark = androidx.compose.foundation.isSystemInDarkTheme()
                        val dot = if (e.command is GateCommand.Close) (if (dark) BramaColors.closeDark else BramaColors.close) else BramaColors.open
                        Box(Modifier.size(12.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(historyTitle(e), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(DateFormat.getDateTimeInstance().format(Date(e.timeMillis)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Messages.commandResult(LastCommand(e.command, e.outcome, e.ackResult))?.let { Text(Texts.text(it)) }
                }
            }
        }
    }
}
