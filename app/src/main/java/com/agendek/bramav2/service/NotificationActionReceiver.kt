package com.agendek.bramav2.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agendek.bramav2.graph

/** Przyciski powiadomienia → intencje domeny (spec 4.3 pkt 3); domena ignoruje je poza SHOWN/READY. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = context.graph.repository
        when (intent.action) {
            ACTION_OPEN -> repository.notificationOpen()
            ACTION_CLOSE -> repository.notificationClose()
            // Użytkownik odrzucił powiadomienie bez otwierania aplikacji (spec 4.3 pkt 4).
            ACTION_DISMISSED -> repository.notificationDismissed()
        }
    }

    companion object {
        const val ACTION_OPEN = "com.agendek.bramav2.action.OPEN"
        const val ACTION_CLOSE = "com.agendek.bramav2.action.CLOSE"
        const val ACTION_DISMISSED = "com.agendek.bramav2.action.DISMISSED"

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            when (action) { ACTION_OPEN -> 1; ACTION_CLOSE -> 2; else -> 3 },
            Intent(context, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
