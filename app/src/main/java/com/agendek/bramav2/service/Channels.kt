package com.agendek.bramav2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Kanały powiadomień: połączenie (usługa, przyciski) i ostrzeżenia (np. DISARMED/REBOOT dla właściciela, spec 3). */
object Channels {
    const val CONNECTION = "gate_connection"
    const val WARNINGS = "gate_warnings"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CONNECTION, "Połączenie z bramą", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(WARNINGS, "Ostrzeżenia", NotificationManager.IMPORTANCE_HIGH))
    }
}
