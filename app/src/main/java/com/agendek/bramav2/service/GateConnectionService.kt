package com.agendek.bramav2.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.agendek.bramav2.MainActivity
import com.agendek.bramav2.R
import com.agendek.bramav2.domain.GateUiState
import com.agendek.bramav2.domain.PresenceNotification
import com.agendek.bramav2.graph
import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Usługa `connectedDevice` (spec 4.2, 4.3): trzyma proces przy życiu, gdy łącze jest potrzebne poza ekranem, i jest
 * jedynym powiadomieniem połączenia — w zasięgu z przyciskami OTWÓRZ/ZAMKNIJ (także na zablokowanym ekranie).
 */
class GateConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var lastShown: GateNotification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = graph.repository
        try {
            val s = repository.state.value
            startForeground(ID, build(gateNotification(s, graph.uiVisible.value), s), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: SecurityException) {
            // Typ connectedDevice wymaga uprawnień Bluetooth; bez nich usługa nie ruszy (domena pokaże problem).
            stopSelf()
            return START_NOT_STICKY
        }
        val g = graph
        collector?.cancel()
        collector = scope.launch {
            combine(repository.state, g.uiVisible) { s, visible -> s to visible }.collect { (s, visible) ->
                // Usługa zatrzymuje się sama, już po startForeground (ServiceController). `stopSelfResult(startId)`
                // nie zatrzyma jej, jeśli w międzyczasie przyszedł nowszy `startForegroundService`: zatrzymanie przed
                // jego `startForeground` kończy się wyjątkiem systemu.
                if (!serviceNeeded(s, visible)) {
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                    return@collect
                }
                // Zablokowane powiadomienia ukrywają przyciski — bramka obecności musi o tym wiedzieć (app_design §5).
                g.refreshPresenceConditions()
                val model = gateNotification(s, visible)
                // Odrzucone powiadomienie (QUIET) nie wraca przy aktualizacji treści (spec 4.3 pkt 5).
                if (model != lastShown && s.presence != PresenceNotification.QUIET) {
                    lastShown = model
                    getSystemService(NotificationManager::class.java).notify(ID, build(model, s))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(notification: GateNotification?, s: GateUiState): Notification {
        val model = notification ?: GateNotification(s.gateName ?: DEFAULT_GATE_NAME, emptyList(), showActions = false)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(model.title)
            .setContentText(model.lines.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(model.lines.joinToString("\n")))
            .setContentIntent(open) // tap otwiera aplikację; zdjęcie powiadomienia robi przejście UI na pierwszy plan
            .setDeleteIntent(NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_DISMISSED))
            // Bez setOngoing(true): od Androida 13 ongoing blokuje przesunięcie powiadomienia usługi (spec 4.3 pkt 4).
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (model.showActions) {
            b.addAction(0, "OTWÓRZ", NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_OPEN))
            b.addAction(0, "ZAMKNIJ", NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_CLOSE))
        }
        return b.build()
    }

    private companion object {
        const val ID = 1
    }
}
