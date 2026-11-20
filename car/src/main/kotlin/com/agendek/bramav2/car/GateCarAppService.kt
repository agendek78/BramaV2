package com.agendek.bramav2.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Aplikacja IoT dla Android Auto (spec 5.1). W debug dowolny host (DHU); w release wyłącznie zaufane hosty
 * z listy biblioteki.
 */
class GateCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = GateSession()
}

class GateSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        GateScreen(carContext, (carContext.applicationContext as CarDependencies).repository)
}
