package com.agendek.bramav2.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.agendek.bramav2.MainActivity
import com.agendek.bramav2.R
import com.agendek.bramav2.domain.ConnectionKeeper
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.system.SystemStatus
import com.agendek.bramav2.domain.DEFAULT_GATE_NAME
import com.agendek.bramav2.domain.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Uruchamia [GateConnectionService], gdy [serviceNeeded] (app_design §4), i realizuje [ConnectionKeeper] (start przed
 * OPEN(true), spec 4.2/7). Usługę zatrzymuje wyłącznie ona sama, po `startForeground` — `stopService` z zewnątrz przed
 * `startForeground` kończy się wyjątkiem systemu. Ostrzeżenia z `GateUiState.notification` trafiają do powiadomień
 * tylko w tle i tylko gdy da się je pokazać; inaczej czekają na snackbar po powrocie do aplikacji.
 */
class ServiceController(private val context: Context, private val uiVisible: StateFlow<Boolean>) : ConnectionKeeper {

    override fun ensureBackgroundConnection(): Boolean = start()

    fun observe(repository: GateRepository, scope: CoroutineScope) {
        // Także przy powrocie UI: start z tła mógł zostać odrzucony, a z pierwszego planu jest dozwolony.
        scope.launch {
            combine(repository.state, uiVisible) { s, visible -> serviceNeeded(s, visible) to visible }
                .distinctUntilChanged()
                .collect { (needed, _) -> if (needed) start() }
        }
        scope.launch {
            combine(repository.state.map { it.notification }, uiVisible) { m, visible -> m.takeIf { !visible } }
                .distinctUntilChanged()
                .collect { message ->
                    val title = repository.state.value.gateName ?: DEFAULT_GATE_NAME
                    if (message != null && SystemStatus.notificationsAllowed(context) && postWarning(title, Texts.text(message))) {
                        repository.dismissNotification()
                    }
                }
        }
    }

    private fun start(): Boolean {
        // Typ connectedDevice wymaga uprawnień Bluetooth; bez nich start zakończyłby się wyjątkiem usługi.
        if (!SystemStatus.bluetoothPermissionsGranted(context)) return false
        return try {
            context.startForegroundService(Intent(context, GateConnectionService::class.java))
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun postWarning(title: String, text: String): Boolean {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Channels.WARNINGS)
            .setSmallIcon(R.drawable.ic_stat_gate)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        return runCatching { context.getSystemService(NotificationManager::class.java).notify(WARNING_ID, n) }.isSuccess
    }

    private companion object {
        const val WARNING_ID = 2
    }
}
