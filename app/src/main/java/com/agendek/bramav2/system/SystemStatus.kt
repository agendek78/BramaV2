package com.agendek.bramav2.system

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.agendek.bramav2.service.Channels
import com.agendek.bramav2.domain.GateRepository
import com.agendek.bramav2.domain.SystemProblem

/** Problemy systemowe telefonu dla domeny (spec 7, A27): brak uprawnień Bluetooth i wyłączony Bluetooth. */
class SystemStatus(private val context: Context, private val repository: GateRepository) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Wołane także po zmianie uprawnień i przy każdym ON_START ekranu. */
    fun refresh() {
        repository.reportSystemProblem(currentProblem())
    }

    fun currentProblem(): SystemProblem? = when {
        !bluetoothPermissionsGranted(context) -> SystemProblem.BLUETOOTH_PERMISSION_MISSING
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true -> SystemProblem.BLUETOOTH_OFF
        else -> null
    }

    companion object {
        val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        fun bluetoothPermissionsGranted(context: Context): Boolean =
            BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        /** Zgoda, powiadomienia aplikacji włączone i kanał połączenia nie jest zablokowany (powiadomienie widoczne). */
        fun notificationsAllowed(context: Context): Boolean {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!nm.areNotificationsEnabled()) return false
            return nm.getNotificationChannel(Channels.CONNECTION)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
