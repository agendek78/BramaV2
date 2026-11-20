package com.agendek.bramav2.car

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agendek.bramav2.domain.GateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Powiadomienie „brama w zasięgu” na ekranie samochodu (spec 5.4): [CarNotificationPolicy] na stanie repozytorium
 * i stanie projekcji Android Auto. Działa na wątku głównym ([start] z `Application.onCreate`, zakres na `Main`).
 */
class CarPresenceNotifier(private val context: Context, private val repository: GateRepository) {

    private val policy = CarNotificationPolicy()
    private val projecting = MutableStateFlow(false)
    private val dismissed = MutableStateFlow(0)
    private var lastShown: CarNotification? = null
    private val manager = CarNotificationManager.from(context)

    /** Powiadomienie mogło zostać na ekranie (pozostałość po procesie, nieudane usunięcie) — usuwać, aż się uda. */
    private var cancelPending = true

    /** Wołać na wątku głównym; [scope] z dyspozytorem `Main`. */
    fun start(scope: CoroutineScope) {
        manager.createNotificationChannel(
            // Na telefonie bez heads-up (obok jest powiadomienie z 4.3); w aucie ważność z CarAppExtender.setImportance.
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Brama w zasięgu (samochód)")
                .build(),
        )
        // Pozostałość po poprzednim procesie (np. zabitym przy widocznym powiadomieniu) nie może zostać z przyciskiem.
        tryCancel()
        CarConnection(context).type.observeForever { projecting.value = it == CarConnection.CONNECTION_TYPE_PROJECTION }
        scope.launch {
            combine(repository.state, projecting, dismissed) { s, p, _ -> policy.update(s, p) }.collect(::render)
        }
    }

    /** Powiadomienie pobytu [cycle] odrzucone w aucie lub na telefonie (spec 5.4 pkt 3). */
    fun onDismissedByDriver(cycle: Int) {
        if (policy.onDismissedByDriver(cycle)) dismissed.value++
    }

    private fun render(model: CarNotification?) {
        if (model == null) {
            if (lastShown != null || cancelPending) tryCancel()
            return
        }
        // Porównanie bez znacznika alarmu — zmiana samego alarmu nie wymaga ponownej wysyłki.
        if (model.copy(alert = false) == lastShown?.copy(alert = false)) return
        val text = model.lines.joinToString(" · ")
        val dismiss = CarNotificationActionReceiver.dismissIntent(context, model.cycle)
        val car = CarAppExtender.Builder()
            .setContentTitle(model.title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_gate)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .setChannelId(CHANNEL)
            .setDeleteIntent(dismiss)
        if (model.showOpen) {
            car.addAction(R.drawable.ic_gate, OPEN, CarNotificationActionReceiver.pendingIntent(context, CarNotificationActionReceiver.ACTION_OPEN))
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle(model.title)
            .setContentText(text)
            .setOnlyAlertOnce(!model.alert)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setDeleteIntent(dismiss) // usunięcie kopii na telefonie też kończy powiadomienie w tym pobycie
            .extend(car.build())
        // Zapamiętane dopiero po udanym wysłaniu — nieudane jest ponawiane przy następnej emisji.
        if (runCatching { manager.notify(ID, builder) }.onFailure { Log.w(TAG, "notify failed", it) }.isSuccess) {
            lastShown = model
            cancelPending = false
        }
    }

    /** Nieudane usunięcie jest ponawiane przy kolejnej emisji stanu. */
    private fun tryCancel() {
        val ok = runCatching { manager.cancel(ID) }.onFailure { Log.w(TAG, "cancel failed", it) }.isSuccess
        cancelPending = !ok
        if (ok) lastShown = null
    }

    private companion object {
        const val CHANNEL = "gate_car_presence"
        const val ID = 3
        const val TAG = "CarPresenceNotifier"
        const val OPEN = "OTWÓRZ"
    }
}

/** Akcje powiadomienia w samochodzie: OTWÓRZ → jak OTWÓRZ z 4.3; usunięcie przez kierowcę → ukrycie do końca pobytu. */
class CarNotificationActionReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val deps = context.applicationContext as CarDependencies
        when (intent.action) {
            ACTION_OPEN -> deps.repository.notificationOpen()
            ACTION_DISMISSED -> deps.carNotifier.onDismissedByDriver(intent.getIntExtra(EXTRA_CYCLE, -1))
        }
    }

    companion object {
        const val ACTION_OPEN = "com.agendek.bramav2.car.action.OPEN"
        const val ACTION_DISMISSED = "com.agendek.bramav2.car.action.DISMISSED"
        private const val EXTRA_CYCLE = "cycle"

        /**
         * Osobny `PendingIntent` na pobyt: numer pobytu w URI intentu (dopasowanie `PendingIntent` porównuje dane,
         * nie dodatki), więc stara kopia nigdy nie dostaje nowego numeru ani odwrotnie.
         */
        fun dismissIntent(context: Context, cycle: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            DISMISS_REQUEST,
            Intent(context, CarNotificationActionReceiver::class.java)
                .setAction(ACTION_DISMISSED)
                .setData(Uri.parse("brama-car-dismiss://cycle/$cycle"))
                .putExtra(EXTRA_CYCLE, cycle),
            PendingIntent.FLAG_IMMUTABLE,
        )

        private const val DISMISS_REQUEST = 12

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, CarNotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
